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
package org.jboss.maven.extension.dependency.modelmodifier.versionoverride;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.jboss.maven.extension.dependency.resolver.AbstractEffectiveModelBuilder;
import org.jboss.maven.extension.dependency.util.Log;
import org.jboss.maven.extension.dependency.util.MavenUtil;
import org.jboss.maven.extension.dependency.util.VersionPropertyReader;

/**
 * Overrides plugin versions in a model
 */
public class PluginVersionOverrider
    extends AbstractVersionOverrider
{
    /**
     * A short description of the thing being overridden
     */
    private static final String OVERRIDE_NAME = "plugin";

    /**
     * The String that needs to be prepended a system property to make it a plugin version override. <br />
     * ex: -DpluginVersion:foo:maven-bar-plugin=1.0
     */
    private static final String PLUGIN_VERSION_OVERRIDE_PREFIX = "pluginVersion:";

    /**
     * The name of the property which contains the GAV of the remote pom from which to retrieve plugin management
     * information. <br />
     * ex: -DpluginManagement:org.foo:bar-plugin-mgmt:1.0
     */
    private static final String PLUGIN_MANAGEMENT_POM_PROPERTY = "pluginManagement";

    /**
     * Cache for override properties. Null until getVersionOverrides() is called.
     */
    private Map<String, String> pluginVersionOverrides;

    @Override
    public boolean updateModel( Model model ) throws MavenExecutionException
    {
        Map<String, String> versionOverrides = getVersionOverrides();
        if ( versionOverrides.size() == 0 )
        {
            return false;
        }

        // If the model doesn't have any plugin management set by default, create one for it
        PluginManagement pluginManagement = model.getBuild().getPluginManagement();
        if ( pluginManagement == null )
        {
            pluginManagement = new PluginManagement();
            model.getBuild().setPluginManagement( pluginManagement );
            Log.getLog().debug( "Created new Plugin Management for model" );
        }

        // Override plugin management versions
        applyOverrides( pluginManagement.getPlugins(), versionOverrides );

        // Override plugin versions
        List<Plugin> projectPlugins = model.getBuild().getPlugins();
        applyOverrides( projectPlugins, versionOverrides );

        // Include the overrides in the built files for repeatability
        writeOverrideMap( model, getName(), versionOverrides );

        // Assuming the Model changed since overrides were given
        return true;
    }

    @Override
    public String getName()
    {
        return OVERRIDE_NAME;
    }

    /**
     * Get the set of versions which will be used to override local plugin versions.
     */
    private Map<String, String> getVersionOverrides() throws MavenExecutionException
    {
        if ( pluginVersionOverrides == null )
        {
            pluginVersionOverrides = new HashMap<String, String>();

            Map<String, String> remotePluginOverrides = loadRemotePluginVersionOverrides();
            pluginVersionOverrides.putAll( remotePluginOverrides );

            Map<String, String> propPluginOverrides =
                VersionPropertyReader.getPropertiesByPrefix( PLUGIN_VERSION_OVERRIDE_PREFIX );
            pluginVersionOverrides.putAll( propPluginOverrides );
        }
        return pluginVersionOverrides;
    }

    /**
     * Set the versions of any plugins which match the contents of the list of plugin overrides
     *
     * @param plugins The list of plugins to modify
     * @param pluginVersionOverrides The list of version overrides to apply to the plugins
     */
    private static void applyOverrides( List<Plugin> plugins, Map<String, String> pluginVersionOverrides )
    {
        for ( Plugin plugin : plugins )
        {
            String groupIdArtifactId = plugin.getGroupId() + GAV_SEPERATOR + plugin.getArtifactId();
            if ( pluginVersionOverrides.containsKey( groupIdArtifactId ) )
            {
                String overrideVersion = pluginVersionOverrides.get( groupIdArtifactId );
                plugin.setVersion( overrideVersion );
                Log.getLog().debug( "Altered plugin: " + groupIdArtifactId + "=" + overrideVersion );
            }
        }
    }

    /**
     * Get plugin management version properties from a remote POM
     *
     * @return Map between the GA of the plugin and the version of the plugin. If the system property is not set,
     *         returns an empty map.
     */
    private static Map<String, String> loadRemotePluginVersionOverrides() throws MavenExecutionException
    {
        Properties systemProperties = System.getProperties();
        String pluginMgmtCSV = systemProperties.getProperty( PLUGIN_MANAGEMENT_POM_PROPERTY );

        Map<String, String> versionOverrides = new HashMap<String, String>( 0 );

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
                Log.getLog().warn( "Skipping invalid plugin management GAV: " + nextGAV );
                continue;
            }
            try
            {
                AbstractEffectiveModelBuilder resolver = AbstractEffectiveModelBuilder.getInstance();
                versionOverrides.putAll( resolver.getRemotePluginVersionOverrides( nextGAV ) );
            }
            catch (Exception e )
            {
                Log.getLog().error( "Unable to resolve remote pom: " + e );
                throw new MavenExecutionException("Unable to resolve remote pom", e);
            }
        }
        return versionOverrides;
    }
}
