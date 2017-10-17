/**
 * Copyright (C) 2013-2017 Expedia Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hotels.styx.infrastructure;

import com.google.common.collect.ImmutableSet;
import com.hotels.styx.api.Environment;
import com.hotels.styx.api.Id;
import com.hotels.styx.api.Identifiable;
import com.hotels.styx.api.configuration.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Memory backed registry for {@code T}.
 *
 * @param <T>
 */
public class MemoryBackedRegistry<T extends Identifiable> extends AbstractRegistry<T> {
    /**
     * Factory for creating {@link MemoryBackedRegistry}.
     *
     * @param <T>
     */
    public static class Factory<T extends Identifiable> implements Registry.Factory<T> {
        @Override
        public Registry<T> create(Environment environment, Configuration registryConfiguration) {
            return new MemoryBackedRegistry<>();
        }
    }

    private final Map<Id, T> resources = new HashMap<>();
    private final boolean autoReload;

    public MemoryBackedRegistry() {
        this(true);
    }

    private MemoryBackedRegistry(boolean autoReload) {
        this.autoReload = autoReload;
    }

    public void add(T t) {
        resources.put(t.id(), t);
        if (autoReload) {
            reload();
        }
    }

    public void removeById(Id id) {
        resources.remove(id);

        if (autoReload) {
            reload();
        }
    }

    public void reset() {
        resources.clear();

        if (autoReload) {
            reload();
        }
    }

    @Override
    public void reload(ReloadListener listener) {
        Changes<T> changes = changes(resources.values(), snapshot.get());
        snapshot.set(ImmutableSet.copyOf(resources.values()));
        notifyListeners(changes);

        listener.onChangesApplied();
    }
}