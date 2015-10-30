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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.DefaultModelProblem;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.resolution.ModelResolver;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.internal.impl.DefaultRemoteRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jboss.maven.extension.dependency.util.Log;

/**
 * Class to resolve artifact descriptors (pom files) from a maven repository
 */
public class EffectiveModelBuilder31 extends AbstractEffectiveModelBuilder
{

    private MavenSession session;

    private RepositorySystem repositorySystem;

    private ArtifactResolver resolver;

    private ModelBuilder modelBuilder;

    /**
     * Repositories for downloading remote poms
     */
    private List<RemoteRepository> repositories;

    /**
     * Get list of remote repositories from which to download artifacts
     *
     * @return list of repositories
     */
    private List<RemoteRepository> getRepositories()
    {
        if ( repositories == null )
        {
            repositories = new ArrayList<RemoteRepository>();
        }

        return repositories;
    }

    /**
     * Set the list of remote repositories from which to download dependency management poms.
     *
     * @param repositories
     */
    public void setRepositories( List<RemoteRepository> repositories )
    {
        this.repositories = repositories;
    }

    /**
     * Set the list of remote repositories from which to download dependency management poms.
     *
     * @param repository
     */
    public void addRepository( ArtifactRepository repository )
    {
        RemoteRepository remoteRepo = new RemoteRepository.Builder( repository.getId(), "default", repository.getUrl() ).build();
        getRepositories().add( remoteRepo );
    }

    /**
     * Private constructor for singleton
     */
    private EffectiveModelBuilder31()
    {

    }

    public static void init( MavenSession session, PlexusContainer plexus, ModelBuilder modelBuilder )
        throws ComponentLookupException, PlexusContainerException
    {

        EffectiveModelBuilder31 instance = new EffectiveModelBuilder31();
        instance.session = session;
        instance.repositorySystem = plexus.lookup( RepositorySystem.class );
        instance.resolver = plexus.lookup( ArtifactResolver.class );
        instance.modelBuilder = modelBuilder;
        AbstractEffectiveModelBuilder.instance = instance;
        initRepositories( session.getRequest().getRemoteRepositories() );
    }

    /**
     * Initialize the set of repositories from which to download remote artifacts
     *
     * @param repositories
     */
    private static void initRepositories( List<ArtifactRepository> repositories )
    {
        if ( repositories == null || repositories.size() == 0 )
        {
            // Set default repository list to include Maven central
            String remoteRepoUrl = "http://repo.maven.apache.org/maven2";
            ((EffectiveModelBuilder31 )instance).getRepositories().add( new RemoteRepository.Builder( "central", "default", remoteRepoUrl ).build() );
        }
        for ( ArtifactRepository artifactRepository : repositories )
        {
            ((EffectiveModelBuilder31 )instance).addRepository( artifactRepository );
        }
    }


    @Override
    public Map<String, String> getRemoteDependencyVersionOverrides( String gav )
        throws ArtifactResolutionException, ArtifactDescriptorException, ModelBuildingException
    {
        Map<String, String> versionOverrides = new HashMap<String, String>();

        Log.getLog().debug( "Resolving dependency management GAV: " + gav );
        Artifact artifact = resolvePom( gav );

        ModelResolver modelResolver = this.newModelResolver();

        Model effectiveModel = buildModel( artifact.getFile(), modelResolver );
        Log.getLog().debug( "Built model for project: " + effectiveModel.getName() );

        if ( effectiveModel.getDependencyManagement() == null )
        {
            ModelProblem dmp = new DefaultModelProblem(
                                         "Attempting to align to a BOM that does not have a dependencyManagement section",
                                         null, null, -1, -1, null );
            throw new ModelBuildingException( effectiveModel, effectiveModel.getId(), Collections.singletonList( dmp ) );
        }

        for ( org.apache.maven.model.Dependency dep : effectiveModel.getDependencyManagement().getDependencies() )
        {
            String groupIdArtifactId = dep.getGroupId() + ":" + dep.getArtifactId();
            versionOverrides.put( groupIdArtifactId, dep.getVersion() );
            Log.getLog().debug( "Added version override for: " + groupIdArtifactId + ":" + dep.getVersion() );
        }

        return versionOverrides;
    }

    public Map<String, String> getRemoteDependencyVersionOverridesOld( String gav )
        throws ArtifactResolutionException, ArtifactDescriptorException
    {
        ArtifactDescriptorResult descResult = resolveRemoteArtifactDescriptor( gav );
        Map<String, String> versionOverrides = new HashMap<String, String>();

        for ( Dependency dep : descResult.getManagedDependencies() )
        {
            Artifact artifact = dep.getArtifact();
            String groupIdArtifactId = artifact.getGroupId() + ":" + artifact.getArtifactId();
            String version = artifact.getVersion();
            versionOverrides.put( groupIdArtifactId, version );
        }

        return versionOverrides;
    }


    @Override
    public Properties getRemotePropertyMappingOverrides( String gav )
        throws ArtifactResolutionException, ArtifactDescriptorException, ModelBuildingException
    {
        Log.getLog().debug( "Resolving remote property mapping POM: " + gav );

        Artifact artifact = resolvePom( gav );

        ModelResolver modelResolver = this.newModelResolver();

        Model effectiveModel = buildModel( artifact.getFile(), modelResolver );

        Properties versionOverrides = effectiveModel.getProperties();

        Log.getLog().debug( "Returning override of " + versionOverrides);

        return versionOverrides;
    }

    public Map<String, String> getRemotePluginVersionOverrides( String gav )
        throws ArtifactResolutionException, ArtifactDescriptorException, ModelBuildingException

    {
        Log.getLog().debug( "Resolving remote plugin management POM: " + gav );

        Artifact artifact = resolvePom( gav );

        ModelResolver modelResolver = this.newModelResolver();

        Model effectiveModel = buildModel( artifact.getFile(), modelResolver );

        List<Plugin> plugins = effectiveModel.getBuild().getPluginManagement().getPlugins();

        Map<String, String> versionOverrides = new HashMap<String, String>();

        for ( Plugin plugin : plugins )
        {
            String groupIdArtifactId = plugin.getGroupId() + ":" + plugin.getArtifactId();
            versionOverrides.put( groupIdArtifactId, plugin.getVersion() );
        }

        return versionOverrides;

    }

    public ArtifactDescriptorResult resolveRemoteArtifactDescriptor( String gav )
        throws ArtifactResolutionException, ArtifactDescriptorException

    {
        Log.getLog().debug( "Resolving remote POM: " + gav );

        RepositorySystemSession repoSession = extractRepositorySystemSession(session);

        Artifact artifact = new DefaultArtifact( gav );

        ArtifactDescriptorRequest descRequest = new ArtifactDescriptorRequest();
        descRequest.setArtifact( artifact );
        descRequest.setRepositories( getRepositories() );

        ArtifactDescriptorResult descResult = repositorySystem.readArtifactDescriptor( repoSession, descRequest );
        for ( Dependency dep : descResult.getManagedDependencies() )
        {
            Log.getLog().info( "Remote managed dep: " + dep );
        }

        Log.getLog().debug( artifact + " resolved to  " + artifact.getFile() );

        return descResult;
    }

    /**
     * Build the effective model for the given pom file
     *
     * @param pomFile
     * @return effective pom model
     * @throws ModelBuildingException
     */
    private Model buildModel( File pomFile, ModelResolver modelResolver )
        throws ModelBuildingException
    {
        ModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setPomFile( pomFile );
        request.setModelResolver( modelResolver );
        request.setValidationLevel( ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_3_0 );
        request.setTwoPhaseBuilding( false ); // Resolve the complete model in one step
        request.setSystemProperties( System.getProperties() );
        ModelBuildingResult result = modelBuilder.build( request );
        return result.getEffectiveModel();
    }

    /**
     * Resolve the pom file for a given GAV
     *
     * @param gav must be in the format groupId:artifactId:version
     * @return The resolved pom artifact
     * @throws ArtifactResolutionException
     */
    private Artifact resolvePom( String gav )
        throws ArtifactResolutionException
    {
        String[] gavParts = gav.split( ":" );
        String groupId = gavParts[0];
        String artifactId = gavParts[1];
        String version = gavParts[2];
        String extension = "pom";

        Artifact artifact = new DefaultArtifact( groupId, artifactId, extension, version );
        artifact = resolveArtifact( artifact );

        return artifact;
    }

    /**
     * Resolve artifact from the remote repository
     *
     * @param artifact
     * @return
     * @throws ArtifactResolutionException
     */
    private Artifact resolveArtifact( Artifact artifact )
        throws ArtifactResolutionException
    {
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact( artifact );
        request.setRepositories( getRepositories() );

        RepositorySystemSession repositorySession = extractRepositorySystemSession(session);
        ArtifactResult result = resolver.resolveArtifact( repositorySession, request );
        return result.getArtifact();
    }

    private ModelResolver newModelResolver() throws ArtifactResolutionException
    {
        RemoteRepositoryManager repoMgr = new DefaultRemoteRepositoryManager();
        ModelResolver modelResolver =
            new BasicModelResolver31(extractRepositorySystemSession(session), resolver, repoMgr, getRepositories() );

        return modelResolver;
    }

    private RepositorySystemSession extractRepositorySystemSession(MavenSession session) throws ArtifactResolutionException {
        try {
            // NB: The return type of MavenSession.getRepositorySession() changed along with the switch from sonatype to eclipse.
            // Since we are compiling against Maven 3.0.4 (still using sonatype), direct invokation runnig with 3.1+ will would cause
            // a java.lang.NoSuchMerhodError - Therefore, we resort to reflection:
            return (RepositorySystemSession) session.getClass().getMethod("getRepositorySession").invoke(session);
        } catch (Exception ex) {
            throw new ArtifactResolutionException(Collections.<ArtifactResult>emptyList(), "Unable to access RepositorySystemSession", ex);
        }
    }
}
