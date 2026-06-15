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

package com.alibaba.nacos.plugin.ai.storage.spi;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.plugin.ai.storage.model.StorageKey;

/**
 * AI resource storage SPI interface.
 *
 * <p>Similar to Nacos's multi-datasource/multi-storage implementation, each storage provider implements this interface.
 * It only cares about how to read/write by key, and is designed for generic AI resources (Skill, Prompt, etc.).</p>
 *
 * <p>Implementations should be created via {@link AiResourceStorageBuilder}.</p>
 *
 * @author mosong.lp
 * @since 3.2.0
 */
public interface AiResourceStorage {

    /** Resource type identifier for Skill storage keys. */
    String RESOURCE_TYPE_SKILL = "skill";

    /** Resource type identifier for AgentSpec storage keys. */
    String RESOURCE_TYPE_AGENTSPEC = "agentspec";

    /** Resource type identifier for Prompt storage keys. */
    String RESOURCE_TYPE_PROMPT = "prompt";

    /**
     * Build a provider-agnostic {@link StorageKey} with the typed 5-part key format.
     *
     * <p>Key format: {@code namespaceId:resourceType:name:version:filePath}.
     * Each storage implementation parses this key according to its own path conventions.</p>
     *
     * @param provider     storage provider type, e.g. "nacos_config", "local_disk"
     * @param namespaceId  namespace
     * @param resourceType resource type ("skill", "agentspec", "prompt")
     * @param name         resource name
     * @param version      version string
     * @param filePath     relative file path within the resource version
     * @return a new StorageKey instance
     */
    static StorageKey buildStorageKey(String provider, String namespaceId, String resourceType,
            String name, String version, String filePath) {
        String key = namespaceId + ":" + resourceType + ":" + name + ":" + version + ":" + filePath;
        return new StorageKey(provider, key);
    }

    /**
     * Type identifier, corresponding to {@link StorageKey#getProvider()}.
     *
     * @return storage provider type, e.g. "nacos_config", "oss"
     */
    String type();
    
    /**
     * Save content to storage.
     *
     * @param storageKey the storage key identifying the resource location
     * @param content    the content to save as byte array
     * @throws NacosException if save operation fails
     */
    void save(StorageKey storageKey, byte[] content) throws NacosException;
    
    /**
     * Get content from storage.
     *
     * @param storageKey the storage key identifying the resource location
     * @return the content as byte array, or null if not found
     * @throws NacosException if get operation fails
     */
    byte[] get(StorageKey storageKey) throws NacosException;
    
    /**
     * Delete content from storage.
     *
     * @param storageKey the storage key identifying the resource location
     * @throws NacosException if delete operation fails
     */
    void delete(StorageKey storageKey) throws NacosException;
}
