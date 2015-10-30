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
package org.jboss.maven.extension.dependency.modelmodifier.propertyoverride;

import java.util.Properties;
import org.apache.maven.execution.MavenSession;
import org.jboss.maven.extension.dependency.modelmodifier.SessionModifier;
import org.jboss.maven.extension.dependency.resolver.AbstractEffectiveModelBuilder;
import org.jboss.maven.extension.dependency.util.Log;
import org.jboss.maven.extension.dependency.util.MavenUtil;

/**
 * Overrides properties in a model
 */
public class PropertyMappingOverrider
    implements SessionModifier
{
    /**
     * A short description of the thing being overridden
     */
    private static final String OVERRIDE_NAME = "property";

    /**
     * The name of the property which contains the GAV of the remote pom from which to retrieve property mapping
     * information. <br />
     * ex: -DpropertyManagement:org.foo:bar-property-mgmt:1.0
     */
    private static final String PROPERTY_MANAGEMENT_POM_PROPERTY = "propertyManagement";

    /**
     * Cache for override properties. Null until getVersionOverrides() is called.
     */
    private Properties propertyMappingOverrides;

    @Override
    public boolean updateSession( MavenSession model )
    {
        Properties versionOverrides = getPropertyOverrides();

        if ( versionOverrides.size() == 0 )
        {
            return false;
        }
        model.getUserProperties().putAll(versionOverrides);

        if (Log.getLog().isDebugEnabled())
        {
            StringBuffer sb = new StringBuffer("Got property overrides ");
            for (String s : versionOverrides.stringPropertyNames())
            {
                sb.append("\n\t" + s + " = " + versionOverrides.getProperty(s));
            }
            Log.getLog().debug(sb.toString());
        }

        // Assuming the Model changed since overrides were given
        return true;
    }

    @Override
    public String getName()
    {
        return OVERRIDE_NAME;
    }

    /**
     * Get the set of versions which will be used to override local property versions.
     */
    private Properties getPropertyOverrides()
    {
        if ( propertyMappingOverrides == null )
        {
            propertyMappingOverrides = new Properties();

            Properties remotePropertyOverrides = loadRemotePropertyMappingOverrides();
            propertyMappingOverrides.putAll( remotePropertyOverrides );
        }
        return propertyMappingOverrides;
    }

    /**
     * Get property mappings from a remote POM
     *
     * @return Map between the GA of the plugin and the version of the plugin. If the system property is not set,
     *         returns an empty map.
     */
    private static Properties loadRemotePropertyMappingOverrides()
    {
        Properties systemProperties = System.getProperties();
        String pluginMgmtCSV = systemProperties.getProperty( PROPERTY_MANAGEMENT_POM_PROPERTY );

        Properties versionOverrides = new Properties();

        if ( pluginMgmtCSV == null )
        {
            return versionOverrides;
        }

        String[] pluginMgmtPomGAVs = pluginMgmtCSV.split( "," );

        // Iterate in reverse order so that the first GAV in the list overwrites the last
        for ( int i = ( pluginMgmtPomGAVs.length - 1 ); i > -1; --i )
        {
            String nextGAV = pluginMgmtPomGAVs[i];

            if ( !MavenUtil.validGav( nextGAV ) )
            {
                Log.getLog().warn( "Skipping invalid remote plugin management GAV: " + nextGAV );
                continue;
            }
            try
            {
                AbstractEffectiveModelBuilder resolver = AbstractEffectiveModelBuilder.getInstance();
                versionOverrides.putAll( resolver.getRemotePropertyMappingOverrides( nextGAV ) );
            }
            catch (Exception e )
            {
                Log.getLog().warn( "Unable to resolve remote pom: " + e, e);
            }
        }
        return versionOverrides;
    }
}
