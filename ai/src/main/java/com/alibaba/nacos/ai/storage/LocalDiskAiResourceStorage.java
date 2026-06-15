/*
 * Copyright 1999-2025 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.ai.storage;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.plugin.ai.storage.model.StorageKey;
import com.alibaba.nacos.plugin.ai.storage.spi.AiResourceStorage;
import com.alibaba.nacos.sys.env.EnvUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Local disk based {@link AiResourceStorage} implementation.
 *
 * <p>Stores AI resource content under {@code {nacos.home}/data/ai-resources/}.
 * Each file is mapped from the StorageKey format
 * {@code namespaceId:resourceType:name:version:filePath} to the filesystem path
 * {@code {root}/{namespaceId}/{resourceType}/{name}/{version}/{filePath}}.</p>
 *
 * @author nacos
 * @since 3.2.2
 */
public class LocalDiskAiResourceStorage implements AiResourceStorage {
    
    public static final String TYPE = "local_disk";
    
    private final Path rootPath;
    
    public LocalDiskAiResourceStorage() {
        this.rootPath =
            Paths.get(EnvUtil.getNacosHome(), "data" + java.io.File.separator + "ai-resources");
    }
    
    @Override
    public String type() {
        return TYPE;
    }
    
    @Override
    public void save(StorageKey storageKey, byte[] content) throws NacosException {
        Path filePath = resolvePath(storageKey);
        try {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, content, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new NacosException(NacosException.SERVER_ERROR,
                "Failed to save file: " + filePath, e);
        }
    }
    
    @Override
    public byte[] get(StorageKey storageKey) throws NacosException {
        Path filePath = resolvePath(storageKey);
        if (!Files.exists(filePath)) {
            return null;
        }
        try {
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            throw new NacosException(NacosException.SERVER_ERROR,
                "Failed to read file: " + filePath, e);
        }
    }
    
    @Override
    public void delete(StorageKey storageKey) throws NacosException {
        Path filePath = resolvePath(storageKey);
        try {
            Files.deleteIfExists(filePath);
            cleanupEmptyParents(filePath);
        } catch (IOException e) {
            throw new NacosException(NacosException.SERVER_ERROR,
                "Failed to delete file: " + filePath, e);
        }
    }
    
    /**
     * Resolve a StorageKey to a filesystem path.
     *
     * <p>Key format: {@code namespaceId:resourceType:name:version:filePath}</p>
     */
    Path resolvePath(StorageKey storageKey) {
        String[] parts = storageKey.getKey().split(":", 5);
        if (parts.length != 5) {
            throw new IllegalArgumentException(
                "Invalid StorageKey.key for local_disk, expected "
                    + "namespaceId:resourceType:name:version:filePath, got: "
                    + storageKey.getKey());
        }
        return rootPath.resolve(parts[0]).resolve(parts[1]).resolve(parts[2])
            .resolve(parts[3]).resolve(parts[4]);
    }
    
    /**
     * Walk up from the file's parent directory and delete empty directories up to
     * (but not including) the rootPath. Best-effort: failures are silently ignored.
     */
    void cleanupEmptyParents(Path filePath) {
        Path dir = filePath.getParent();
        while (dir != null && dir.startsWith(rootPath) && !dir.equals(rootPath)) {
            try {
                String[] list = dir.toFile().list();
                if (list != null && list.length == 0) {
                    Files.deleteIfExists(dir);
                } else {
                    break;
                }
            } catch (IOException ignored) {
                break;
            }
            dir = dir.getParent();
        }
    }
}
