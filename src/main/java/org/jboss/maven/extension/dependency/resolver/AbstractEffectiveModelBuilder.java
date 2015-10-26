/**
 * Copyright (C) 2013 Red Hat, Inc.
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
package org.jboss.maven.extension.dependency.resolver;

import java.util.Map;
import java.util.Properties;

public abstract class AbstractEffectiveModelBuilder
{
    protected static AbstractEffectiveModelBuilder instance;

    /**
     * Return the instance. Will return "null" until init() has been called.
     *
     * @return the initialized instance or null if it hasn't been initialized yet
     */
    public static AbstractEffectiveModelBuilder getInstance() {
        return instance;
    }

    public abstract Properties getRemotePropertyMappingOverrides( String gav ) throws Exception;

    public abstract Map<String, String> getRemotePluginVersionOverrides( String gav ) throws Exception;

    public abstract Map<String, String> getRemoteDependencyVersionOverrides( String gav ) throws Exception;

}
