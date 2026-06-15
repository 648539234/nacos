# LocalDiskAiResourceStorage 设计方案

## Context

Skill/AgentSpec/Prompt 的文件内容目前通过 `NacosConfigAiResourceStorage` 存储，底层走 Nacos Config → 数据库，每个文件写入后还需 `Thread.sleep(200)` 等待异步 dump 生效。大 Skill（几十上百个资源文件）上传时性能极差，并行 IO 线程数调大后还会耗尽 DB 连接池。

目标：实现本地磁盘存储 provider，standalone 模式默认启用，文件内容直接写磁盘，元数据仍走 `AiResourcePersistService`（DB）。

## 改动清单

### 1. 将 `buildStorageKey` 提升到 `AiResourceStorage` 接口

**文件**: `plugin/ai/src/main/java/com/alibaba/nacos/plugin/ai/storage/spi/AiResourceStorage.java`

添加静态工厂方法，构造 provider-agnostic 的 `StorageKey`。Key 格式统一为 5-part：`namespaceId:resourceType:name:version:filePath`。

```java
// 新增静态方法
static StorageKey buildStorageKey(String provider, String namespaceId,
    String resourceType, String name, String version, String filePath) {
    String key = namespaceId + ":" + resourceType + ":" + name + ":" + version + ":" + filePath;
    return new StorageKey(provider, key);
}
```

同时保留 `NacosConfigAiResourceStorage` 中的现有方法作为委托（兼容旧调用方，后续逐步替换）。

### 2. 新增 `LocalDiskAiResourceStorage`

**新文件**: `ai/src/main/java/com/alibaba/nacos/ai/storage/LocalDiskAiResourceStorage.java`

实现 `AiResourceStorage`，type = `"local_disk"`。

- 存储根目录：`{nacos.home}/data/ai-resources/`
- Key 解析：将 `StorageKey.key`（格式 `namespaceId:resourceType:name:version:filePath`）映射为文件系统路径 `{root}/{namespaceId}/{resourceType}/{name}/{version}/{filePath}`
- `save()`: 创建父目录 + `Files.write()`
- `get()`: `Files.readAllBytes()`，文件不存在返回 null
- `delete()`: `Files.deleteIfExists()`，并清理空父目录（best-effort）

### 3. 新增 `LocalDiskAiResourceStorageBuilder`

**新文件**: `ai/src/main/java/com/alibaba/nacos/ai/storage/LocalDiskAiResourceStorageBuilder.java`

实现 `AiResourceStorageBuilder`，type = `"local_disk"`，`build()` 直接 `new LocalDiskAiResourceStorage()`。

### 4. 注册 SPI

**文件**: `ai/src/main/resources/META-INF/services/com.alibaba.nacos.plugin.ai.storage.spi.AiResourceStorageBuilder`

添加一行：`com.alibaba.nacos.ai.storage.LocalDiskAiResourceStorageBuilder`

### 5. 修改三个 `resolveXxxStorageProvider()` 方法

**文件**:
- `ai/src/main/java/com/alibaba/nacos/ai/service/skills/SkillOperationServiceImpl.java` (`resolveSkillStorageProvider`)
- `ai/src/main/java/com/alibaba/nacos/ai/service/agentspecs/AgentSpecOperationServiceImpl.java` (`resolveStorageProvider`)
- `ai/src/main/java/com/alibaba/nacos/ai/service/prompt/PromptOperationServiceImpl.java` (`resolvePromptStorageProvider`)

逻辑统一为：
```java
private static String resolveSkillStorageProvider() {
    String provider = EnvUtil.getProperty(SKILL_STORAGE_PROVIDER_CONFIG_KEY);
    if (StringUtils.isNotBlank(provider)) {
        return provider.trim();
    }
    return EnvUtil.getStandaloneMode() ? "local_disk" : "nacos_config";
}
```

### 6. 更新 `PromptDataMigrationTask` 中的 `resolveStorageProvider`

**文件**: `ai/src/main/java/com/alibaba/nacos/ai/config/PromptDataMigrationTask.java`

改为与第 5 点一致的逻辑。

### 7. `SkillIndexManifestService` 保持不变

Manifest 是轻量索引元数据，继续走 Nacos Config，不受此次变更影响。

## 注意事项

- `SkillOperationServiceImpl` 中 `writeSkillToStorage` 使用并行 IO 线程池写文件。切换到本地磁盘后，`Thread.sleep(200)` 不再被调用（`LocalDiskAiResourceStorage.save()` 没有 sync 逻辑），IO 性能大幅提升。并行度可以进一步调优，但已不是瓶颈。
- `NacosConfigAiResourceStorage` 及其 Builder 保持不变，集群模式仍然可用。
- 旧 `storageJson` 中记录的 provider 字段与新 provider 不匹配时，读取会路由到错误的存储实现。由于用户选择一刀切（新建数据库），无兼容性问题。
- `buildStorageKey` 接口提升后，`NacosConfigAiResourceStorage` 中的现有静态方法保留为委托，后续可逐步清理调用方。

## 文件变更汇总

| 操作 | 文件 |
|------|------|
| 修改 | `plugin/ai/.../spi/AiResourceStorage.java` — 添加 `buildStorageKey` 静态方法 |
| 新增 | `ai/.../storage/LocalDiskAiResourceStorage.java` |
| 新增 | `ai/.../storage/LocalDiskAiResourceStorageBuilder.java` |
| 修改 | `ai/.../resources/META-INF/services/...AiResourceStorageBuilder` — 注册新 builder |
| 修改 | `ai/.../service/skills/SkillOperationServiceImpl.java` — `resolveSkillStorageProvider()` |
| 修改 | `ai/.../service/agentspecs/AgentSpecOperationServiceImpl.java` — `resolveStorageProvider()` |
| 修改 | `ai/.../service/prompt/PromptOperationServiceImpl.java` — `resolvePromptStorageProvider()` |
| 修改 | `ai/.../config/PromptDataMigrationTask.java` — `resolveStorageProvider()` |

## 验证方式

1. 单元测试：`LocalDiskAiResourceStorage` 的 save/get/delete 测试
2. 集成测试：standalone 模式启动，上传一个 Skill，验证 `{nacos.home}/data/ai-resources/` 下生成了正确的目录结构和文件内容
3. 验证读取：通过 API 查询已上传的 Skill，确认内容正确返回
4. 验证删除：删除 Skill 后确认磁盘文件被清理
5. 验证集群模式：`-Dnacos.standalone=false` 时仍然走 `nacos_config`
6. 验证显式配置：设置 `nacos.ai.skill.storage.provider=nacos_config` 时在 standalone 下也走 Config