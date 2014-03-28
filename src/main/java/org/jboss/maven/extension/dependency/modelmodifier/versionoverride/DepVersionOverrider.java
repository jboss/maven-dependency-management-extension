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

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelBuildingException;
import org.jboss.maven.extension.dependency.resolver.EffectiveModelBuilder;
import org.jboss.maven.extension.dependency.util.Log;
import org.jboss.maven.extension.dependency.util.MavenUtil;
import org.jboss.maven.extension.dependency.util.VersionPropertyReader;
import org.sonatype.aether.resolution.ArtifactDescriptorException;
import org.sonatype.aether.resolution.ArtifactResolutionException;

/**
 * Overrides dependency versions in a model
 */
public class DepVersionOverrider
    extends AbstractVersionOverrider
{
    /**
     * The String that needs to be prepended a system property to make it a version override. <br />
     * ex: -Dversion:junit:junit=4.10
     */
    private static final String DEPENDENCY_VERSION_OVERRIDE_PREFIX = "version:";

    /**
     * The name of the property that specifies whether or not to override transitive dependencies in the build. This
     * causes non-matching dependencies to be added to the dependency management section of the pom. Default is true. <br />
     * ex: -overrideTransitive=true
     */
    private static final String OVERRIDE_TRANSITIVE = "overrideTransitive";

    /**
     * The name of the property which contains the GAV of the remote pom from which to retrieve dependency management
     * information. <br />
     * ex: -DdependencyManagement:org.foo:bar-dep-mgmt:1.0
     */
    private static final String DEPENDENCY_MANAGEMENT_POM_PROPERTY = "dependencyManagement";

    /**
     * A short description of the thing being overridden
     */
    private static final String OVERRIDE_NAME = "dependency";

    /**
     * Cache for override properties. Null until getVersionOverrides() is called.
     */
    private Map<String, String> dependencyVersionOverrides;

    /**
     * The set of projects currently in the reactor. The versions of these projects should not be overridden.
     */
    private Set<String> reactorProjects;

    /**
     * Modify model's dependency management and direct dependencies.
     */
    @Override
    public boolean updateModel( Model model )
    {
        Map<String, String> versionOverrides = getVersionOverrides();
        if ( versionOverrides.size() == 0 )
        {
            return false;
        }

        versionOverrides = removeReactorGAs( versionOverrides );

        String projectGA = model.getGroupId() + ":" + model.getArtifactId();

        versionOverrides = applyModuleVersionOverrides( projectGA, versionOverrides );

        // Add/override a property to the build for each override
        addVersionOverrideProperties( versionOverrides, model.getProperties() );

        // If the model doesn't have any Dependency Management set by default, create one for it
        DependencyManagement dependencyManagement = model.getDependencyManagement();
        if ( dependencyManagement == null )
        {
            dependencyManagement = new DependencyManagement();
            model.setDependencyManagement( dependencyManagement );
            Log.getLog().debug( "Added <DependencyManagement/> for current project" );
        }

        // Apply overrides to project dependency management
        List<Dependency> dependencies = dependencyManagement.getDependencies();
        Map<String, String> nonMatchingVersionOverrides = applyOverrides( dependencies, versionOverrides );
        if ( overrideTransitive() )
        {
            // Add dependencies to Dependency Management which did not match any existing dependency
            for ( String groupIdArtifactId : nonMatchingVersionOverrides.keySet() )
            {
                String[] groupIdArtifactIdParts = groupIdArtifactId.split( ":" );

                Dependency newDependency = new Dependency();
                newDependency.setGroupId( groupIdArtifactIdParts[0] );
                newDependency.setArtifactId( groupIdArtifactIdParts[1] );

                String artifactVersion = nonMatchingVersionOverrides.get( groupIdArtifactId );
                newDependency.setVersion( artifactVersion );

                dependencyManagement.getDependencies().add( newDependency );
                Log.getLog().debug( "New entry added to <DependencyManagement/> - " + groupIdArtifactId + ":" +
                                        artifactVersion );
            }
        }
        else
        {
            Log.getLog().debug( "Non-matching dependencies ignored." );
        }

        // Apply overrides to project direct dependencies
        List<Dependency> projectDependencies = model.getDependencies();
        applyOverrides( projectDependencies, versionOverrides );

        // Include the overrides in the built files for repeatability
        writeOverrideMap( model, getName(), versionOverrides );

        // Assuming the Model changed since overrides were given
        return true;
    }

    private Set<String> getReactorProjects()
    {
        if ( reactorProjects == null || reactorProjects.size() == 0 )
        {
            String[] reactorProjectGAs = System.getProperty( "reactorProjectGAs" ).split( "," );
            reactorProjects = new HashSet<String>( Arrays.asList( reactorProjectGAs ) );
        }
        return reactorProjects;
    }

    @Override
    public String getName()
    {
        return OVERRIDE_NAME;
    }

    /**
     * Whether to override unmanaged transitive dependencies in the build. Has the effect of adding (or not) new entries
     * to dependency management when no matching dependency is found in the pom. Defaults to true.
     *
     * @return
     */
    private boolean overrideTransitive()
    {
        String overrideTransitive = System.getProperties().getProperty( OVERRIDE_TRANSITIVE, "true" );
        return overrideTransitive.equals( "true" );
    }

    /**
     * Get the set of versions which will be used to override local dependency versions. This is the full set of version
     * overrides from system properties and remote poms.
     *
     * The format of the key is "groupId:artifactId[@moduleGroupId:moduleArtifactId]"
     * The value is the version string
     */
    private Map<String, String> getVersionOverrides()
    {
        if ( dependencyVersionOverrides == null )
        {
            dependencyVersionOverrides = new HashMap<String, String>();

            Map<String, String> remoteDepOverrides = loadRemoteDepVersionOverrides();
            dependencyVersionOverrides.putAll( remoteDepOverrides );

            Map<String, String> propDepOverrides =
                VersionPropertyReader.getPropertiesByPrefix( DEPENDENCY_VERSION_OVERRIDE_PREFIX );
            dependencyVersionOverrides.putAll( propDepOverrides );

        }
        return dependencyVersionOverrides;
    }

    /**
     * Remove version overrides which refer to projects in the current reactor.
     * Projects in the reactor include things like inter-module dependencies
     * which should never be overridden.
     *
     * @param versionOverrides
     * @return A new Map with the reactor GAs removed.
     */
    private Map<String, String> removeReactorGAs( Map<String, String> versionOverrides )
    {
        Map<String, String> reducedVersionOverrides = new HashMap<String, String>( versionOverrides );
        Set<String> reactorProjects = getReactorProjects();
        for ( String reactorGA : reactorProjects )
        {
            reducedVersionOverrides.remove( reactorGA );
        }
        return reducedVersionOverrides;
    }

    /**
     * Remove module overrides which do not apply to the current module. Searches the full list of version overrides
     * for any keys which contain the '@' symbol.  Removes these from the version overrides list, and add them back
     * without the '@' symbol only if they apply to the current module.
     *
     * @param versionOverides The full list of version overrides, both global and module specific
     * @return The map of global and module specific overrides which apply to the given module
     */
    private Map<String, String> applyModuleVersionOverrides( String projectGA, Map<String, String> versionOverrides )
    {
        Map<String, String> moduleVersionOverrides = new HashMap<String, String>( versionOverrides );
        for ( String currentKey : versionOverrides.keySet() )
        {
            if ( currentKey.contains( "@" ) )
            {
                moduleVersionOverrides.remove( currentKey );
                String[] artifactAndModule = currentKey.split( "@" );
                String artifactGA = artifactAndModule[0];
                String moduleGA = artifactAndModule[1];
                if ( moduleGA.equals( projectGA ) )
                {
                    moduleVersionOverrides.put( artifactGA, versionOverrides.get( currentKey ) );
                }
            }
        }
        return moduleVersionOverrides;
    }

    /***
     * Add properties to the build which match the version overrides.
     * The property names are in the format
     */
    private void addVersionOverrideProperties( Map<String, String> overrides, Properties props )
    {
        String propPrefix = getVersionPropertyPrefix();
        String gaSeparator = getGASeparator();
        String propSuffix = getVersionPropertySuffix();

        for (String currentGA : overrides.keySet() )
        {
            String versionPropName = propPrefix + currentGA.replace( ":", gaSeparator ) + propSuffix;
            props.setProperty( versionPropName, overrides.get( currentGA ) );
        }
    }

    /**
     * Get the prefix that should be used for version property names
     * @return The prefix set in the system properties or the defult DEPENDENCY_VERSION_OVERRIDE_PREFIX
     */
    private String getVersionPropertyPrefix()
    {
        return System.getProperty( "versionPropertyPrefix", DEPENDENCY_VERSION_OVERRIDE_PREFIX);
    }

    /**
     * Get the groupId/artifactId separator
     * @return The separator set in the system properties, or ":" by default
     */
    private String getGASeparator()
    {
        return System.getProperty( "versionPropertyGASeparator", ":" );
    }

    /**
     * Get the suffix that should be used for version property names
     * @return The suffix set in the system properties or the default empty string
     */
    private String getVersionPropertySuffix()
    {
        return System.getProperty( "versionPropertySuffix", "");
    }

    /**
     * Apply a set of version overrides to a list of dependencies. Return a set of the overrides which were not applied.
     *
     * @param dependencies The list of dependencies
     * @param overrides The map of dependency version overrides
     * @return The map of overrides that were not matched in the dependencies
     */
    private static Map<String, String> applyOverrides( List<Dependency> dependencies, Map<String, String> overrides )
    {
        Set<String> excludes = new HashSet<String>();
        return applyOverrides( dependencies, overrides, excludes );
    }

    /**
     * Apply a set of version overrides to a list of dependencies. Return a set of the overrides which were not applied.
     *
     * @param dependencies The list of dependencies
     * @param overrides The map of dependency version overrides
     * @param excludes A set of GAs to ignore when overridding dep versions
     * @return The map of overrides that were not matched in the dependencies
     */
    private static Map<String, String> applyOverrides( List<Dependency> dependencies, Map<String, String> overrides,
                                                       Set<String> excludes )
    {
        // Duplicate the override map so unused overrides can be easily recorded
        Map<String, String> unmatchedVersionOverrides = new HashMap<String, String>();
        unmatchedVersionOverrides.putAll( overrides );

        // Apply matching overrides to dependencies
        for ( Dependency dependency : dependencies )
        {
            String groupIdArtifactId = dependency.getGroupId() + GAV_SEPERATOR + dependency.getArtifactId();
            if ( overrides.containsKey( groupIdArtifactId ) && !excludes.contains( groupIdArtifactId ) )
            {
                String oldVersion = dependency.getVersion();
                String overrideVersion = overrides.get( groupIdArtifactId );

                if (overrideVersion == null || overrideVersion.length() == 0)
                {
                    Log.getLog().warn("Unable to align to an empty version; ignoring");
                }
                else
                {
                    dependency.setVersion( overrideVersion );
                    Log.getLog().debug( "Altered dependency " + groupIdArtifactId + " " + oldVersion + "->" +
                                        overrideVersion );
                    unmatchedVersionOverrides.remove( groupIdArtifactId );
                }
            }
        }

        return unmatchedVersionOverrides;
    }

    /**
     * Get dependency management version properties from a remote POM
     *
     * @return Map between the GA of the dependency and the version of the dependency. If the property is not set,
     *         returns an empty map
     */
    private static Map<String, String> loadRemoteDepVersionOverrides()
    {
        Properties systemProperties = System.getProperties();
        String depMgmtPomCSV = systemProperties.getProperty( DEPENDENCY_MANAGEMENT_POM_PROPERTY );

        Map<String, String> versionOverrides = new HashMap<String, String>( 0 );

        if ( depMgmtPomCSV == null )
        {
            return versionOverrides;
        }

        String[] depMgmtPomGAVs = depMgmtPomCSV.split( "," );

        // Iterate in reverse order so that the first GAV in the list overwrites the last
        for ( int i = ( depMgmtPomGAVs.length - 1 ); i > -1; --i )
        {
            String nextGAV = depMgmtPomGAVs[i];
            if ( !MavenUtil.validGav( nextGAV ) )
            {
                Log.getLog().warn( "Skipping invalid dependency management GAV: " + nextGAV );
                continue;
            }
            try
            {
                EffectiveModelBuilder resolver = EffectiveModelBuilder.getInstance();
                versionOverrides.putAll( resolver.getRemoteDependencyVersionOverrides( nextGAV ) );
            }
            catch ( ArtifactResolutionException e )
            {
                Log.getLog().warn( "Unable to resolve remote pom: " + e );
            }
            catch ( ArtifactDescriptorException e )
            {
                Log.getLog().warn( "Unable to resolve remote pom: " + e );
            }
            catch ( ModelBuildingException e )
            {
                Log.getLog().warn( "Unable to resolve remote pom: " + e );
            }
        }

        return versionOverrides;
    }
}
