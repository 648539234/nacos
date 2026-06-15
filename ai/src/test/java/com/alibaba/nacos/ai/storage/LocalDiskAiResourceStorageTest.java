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

import com.alibaba.nacos.plugin.ai.storage.model.StorageKey;
import com.alibaba.nacos.plugin.ai.storage.spi.AiResourceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LocalDiskAiResourceStorage}.
 */
class LocalDiskAiResourceStorageTest {
    
    @TempDir
    Path tempDir;
    
    private LocalDiskAiResourceStorage storage;
    
    @BeforeEach
    void setUp() throws Exception {
        storage = new LocalDiskAiResourceStorage() {
            
            @Override
            Path resolvePath(StorageKey storageKey) {
                String[] parts = storageKey.getKey().split(":", 5);
                if (parts.length != 5) {
                    throw new IllegalArgumentException(
                        "Invalid StorageKey.key for local_disk, expected "
                            + "namespaceId:resourceType:name:version:filePath, got: "
                            + storageKey.getKey());
                }
                return tempDir.resolve(parts[0]).resolve(parts[1]).resolve(parts[2])
                    .resolve(parts[3]).resolve(parts[4]);
            }
            
            @Override
            void cleanupEmptyParents(Path filePath) {
                Path dir = filePath.getParent();
                while (dir != null && dir.startsWith(tempDir) && !dir.equals(tempDir)) {
                    try {
                        String[] list = dir.toFile().list();
                        if (list != null && list.length == 0) {
                            java.nio.file.Files.deleteIfExists(dir);
                        } else {
                            break;
                        }
                    } catch (java.io.IOException ignored) {
                        break;
                    }
                    dir = dir.getParent();
                }
            }
        };
    }
    
    @Test
    void testType() {
        assertEquals(LocalDiskAiResourceStorage.TYPE, storage.type());
    }
    
    @Test
    void testSaveAndGet() throws Exception {
        StorageKey key = AiResourceStorage.buildStorageKey(LocalDiskAiResourceStorage.TYPE,
            "ns1", "skill", "my-skill", "0.0.1", "SKILL.md");
        byte[] content = "---\nname: my-skill\n---\n# Hello".getBytes(StandardCharsets.UTF_8);
        
        storage.save(key, content);
        byte[] result = storage.get(key);
        
        assertArrayEquals(content, result);
        
        Path expectedPath = tempDir.resolve("ns1/skill/my-skill/0.0.1/SKILL.md");
        assertTrue(Files.exists(expectedPath));
    }
    
    @Test
    void testGetNonExistentReturnsNull() throws Exception {
        StorageKey key = AiResourceStorage.buildStorageKey(LocalDiskAiResourceStorage.TYPE,
            "ns1", "skill", "nonexistent", "0.0.1", "SKILL.md");
        
        byte[] result = storage.get(key);
        assertNull(result);
    }
    
    @Test
    void testSaveOverwritesExisting() throws Exception {
        StorageKey key = AiResourceStorage.buildStorageKey(LocalDiskAiResourceStorage.TYPE,
            "ns1", "skill", "my-skill", "0.0.1", "SKILL.md");
        byte[] content1 = "version 1".getBytes(StandardCharsets.UTF_8);
        byte[] content2 = "version 2".getBytes(StandardCharsets.UTF_8);
        
        storage.save(key, content1);
        storage.save(key, content2);
        byte[] result = storage.get(key);
        
        assertArrayEquals(content2, result);
    }
    
    @Test
    void testDelete() throws Exception {
        StorageKey key = AiResourceStorage.buildStorageKey(LocalDiskAiResourceStorage.TYPE,
            "ns1", "skill", "my-skill", "0.0.1", "SKILL.md");
        byte[] content = "test".getBytes(StandardCharsets.UTF_8);
        
        storage.save(key, content);
        storage.delete(key);
        
        assertNull(storage.get(key));
    }
    
    @Test
    void testDeleteNonExistentDoesNotThrow() throws Exception {
        StorageKey key = AiResourceStorage.buildStorageKey(LocalDiskAiResourceStorage.TYPE,
            "ns1", "skill", "nonexistent", "0.0.1", "SKILL.md");
        
        storage.delete(key);
    }
    
    @Test
    void testDeleteCleansUpEmptyParents() throws Exception {
        StorageKey key = AiResourceStorage.buildStorageKey(LocalDiskAiResourceStorage.TYPE,
            "ns1", "skill", "my-skill", "0.0.1", "SKILL.md");
        byte[] content = "test".getBytes(StandardCharsets.UTF_8);
        
        storage.save(key, content);
        storage.delete(key);
        
        assertFalse(Files.exists(tempDir.resolve("ns1/skill/my-skill/0.0.1")));
        assertFalse(Files.exists(tempDir.resolve("ns1/skill/my-skill")));
    }
    
    @Test
    void testMultipleFilesInSameVersion() throws Exception {
        StorageKey key1 = AiResourceStorage.buildStorageKey(LocalDiskAiResourceStorage.TYPE,
            "ns1", "skill", "my-skill", "0.0.1", "SKILL.md");
        StorageKey key2 = AiResourceStorage.buildStorageKey(LocalDiskAiResourceStorage.TYPE,
            "ns1", "skill", "my-skill", "0.0.1", "scripts/run.sh");
        
        storage.save(key1, "md".getBytes(StandardCharsets.UTF_8));
        storage.save(key2, "script".getBytes(StandardCharsets.UTF_8));
        
        assertArrayEquals("md".getBytes(StandardCharsets.UTF_8), storage.get(key1));
        assertArrayEquals("script".getBytes(StandardCharsets.UTF_8), storage.get(key2));
    }
    
    @Test
    void testResolvePathInvalidKeyFormat() {
        StorageKey key = new StorageKey(LocalDiskAiResourceStorage.TYPE, "ns1:only:three:parts");
        
        assertThrows(IllegalArgumentException.class, () -> storage.resolvePath(key));
    }
    
    @Test
    void testAgentSpecStorageKey() throws Exception {
        StorageKey key = AiResourceStorage.buildStorageKey(LocalDiskAiResourceStorage.TYPE,
            "ns1", "agentspec", "my-worker", "1.0.0", "manifest.json");
        byte[] content = "{}".getBytes(StandardCharsets.UTF_8);
        
        storage.save(key, content);
        
        Path expectedPath = tempDir.resolve("ns1/agentspec/my-worker/1.0.0/manifest.json");
        assertTrue(Files.exists(expectedPath));
        assertArrayEquals(content, storage.get(key));
    }
    
    @Test
    void testPromptStorageKey() throws Exception {
        StorageKey key = AiResourceStorage.buildStorageKey(LocalDiskAiResourceStorage.TYPE,
            "ns1", "prompt", "greeting", "1.0.0", "content.json");
        byte[] content = "{}".getBytes(StandardCharsets.UTF_8);
        
        storage.save(key, content);
        
        Path expectedPath = tempDir.resolve("ns1/prompt/greeting/1.0.0/content.json");
        assertTrue(Files.exists(expectedPath));
        assertArrayEquals(content, storage.get(key));
    }
}
