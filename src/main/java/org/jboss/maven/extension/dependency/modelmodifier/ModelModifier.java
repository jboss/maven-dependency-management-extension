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
package org.jboss.maven.extension.dependency.modelmodifier;

import org.apache.maven.MavenExecutionException;
import org.apache.maven.model.Model;

/**
 * Interface for classes that modify a built model in some way
 */
public interface ModelModifier
{
    /**
     * Possibly updates a model in some way (may do nothing).
     * 
     * @param model the Model to be modified
     * @return true iff the model changed
     */
    public boolean updateModel( Model model ) throws MavenExecutionException;

    /**
     * Get simple name of this modifier
     * 
     * @return Simple name of this modifier
     */
    public String getName();

}