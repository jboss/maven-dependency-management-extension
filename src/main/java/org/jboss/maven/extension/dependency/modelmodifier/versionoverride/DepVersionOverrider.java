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

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelBuildingException;
import org.jboss.maven.extension.dependency.resolver.EffectiveModelBuilder;
import org.jboss.maven.extension.dependency.util.VersionPropertyReader;
import org.jboss.maven.extension.dependency.util.log.Log;
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
     * The name of the property that specifies whether or not to add non-matching dependencies <br />
     * ex: -DaddNewDeps=true
     */
    private static final String ADD_NON_MATCHING = "addNewDeps";

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

        // If the model doesn't have any Dependency Management set by default, create one for it
        DependencyManagement dependencyManagement = model.getDependencyManagement();
        if ( dependencyManagement == null )
        {
            dependencyManagement = new DependencyManagement();
            model.setDependencyManagement( dependencyManagement );
            Log.getLog().debug( "Created new Dependency Management for model" );
        }

        // Apply overrides to Dependency Management
        List<Dependency> dependencies = dependencyManagement.getDependencies();
        Map<String, String> nonMatchingVersionOverrides = applyOverrides( dependencies, versionOverrides );
        if ( addNewDeps() )
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
                Log.getLog().debug( "New dependency added to Dependency Management: " + groupIdArtifactId + "="
                                    + artifactVersion );
            }
        }
        else
        {
            Log.getLog().debug( "Non-matching dependencies ignored." );
        }

        // Apply overrides to project dependencies
        List<Dependency> projectDependencies = model.getDependencies();
        applyOverrides( projectDependencies, versionOverrides );

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

    private boolean addNewDeps()
    {
        Properties systemProperties = System.getProperties();
        String addNonMatching = systemProperties.getProperty( ADD_NON_MATCHING );
        if ( addNonMatching != null && addNonMatching.equals( "false" ) )
        {
            return false;
        }
        return true;
    }

    /**
     * Get the set of versions which will be used to override local dependency versions.
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
     * Apply a set of version overrides to a list of dependencies. Return a set of the overrides which were not applied.
     * 
     * @param dependencies The list of dependencies
     * @param overrides The map of dependency version overrides
     * @return The map of overrides that were not matched in the dependencies
     */
    private static Map<String, String> applyOverrides( List<Dependency> dependencies, Map<String, String> overrides )
    {
        // Duplicate the override map so unused overrides can be easily recorded
        Map<String, String> nonMatchingVersionOverrides = new HashMap<String, String>();
        nonMatchingVersionOverrides.putAll( overrides );

        // Apply matching overrides to dependencies
        for ( Dependency dependency : dependencies )
        {
            String groupIdArtifactId = dependency.getGroupId() + GAV_SEPERATOR + dependency.getArtifactId();
            if ( overrides.containsKey( groupIdArtifactId ) )
            {
                String artifactVersion = overrides.get( groupIdArtifactId );
                dependency.setVersion( artifactVersion );
                Log.getLog().debug( "Altered dependency: " + groupIdArtifactId + "=" + artifactVersion );
                nonMatchingVersionOverrides.remove( groupIdArtifactId );
            }
        }

        return nonMatchingVersionOverrides;
    }

    /**
     * Get dependency management version properties from a remote POM
     * 
     * @return Map between the GA of the dependency and the version of the dependency.
     */
    private static Map<String, String> loadRemoteDepVersionOverrides()
    {
        Properties systemProperties = System.getProperties();
        String depMgmtPomGAV = systemProperties.getProperty( DEPENDENCY_MANAGEMENT_POM_PROPERTY );

        Map<String, String> versionOverrides = new HashMap<String, String>( 0 );

        if ( depMgmtPomGAV != null )
        {
            try
            {
                EffectiveModelBuilder resolver = EffectiveModelBuilder.getInstance();
                versionOverrides = resolver.getRemoteDependencyVersionOverrides( depMgmtPomGAV );
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
