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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

/**
 * Basic implementation of ModelResolver to resolve pom files from a remote repository.
 */
public class BasicModelResolver31
    implements ModelResolver
{

    private final RepositorySystemSession session;

    private final ArtifactResolver resolver;

    private final RemoteRepositoryManager remoteRepositoryManager;

    private final Set<String> repositoryIds;

    private List<RemoteRepository> repositories;

    public BasicModelResolver31( RepositorySystemSession session, ArtifactResolver resolver,
                               RemoteRepositoryManager remoteRepositoryManager, List<RemoteRepository> repositories )
    {
        this.session = session;
        this.resolver = resolver;
        this.remoteRepositoryManager = remoteRepositoryManager;
        this.repositories = repositories;
        this.repositoryIds = new HashSet<String>();
    }

    private BasicModelResolver31( BasicModelResolver31 original )
    {
        this.session = original.session;
        this.resolver = original.resolver;
        this.remoteRepositoryManager = original.remoteRepositoryManager;
        this.repositories = original.repositories;
        this.repositoryIds = new HashSet<String>( original.repositoryIds );
    }

    @Override
    public void addRepository( Repository repository )
        throws InvalidRepositoryException
    {
        if ( !repositoryIds.add( repository.getId() ) )
        {
            return;
        }

        List<RemoteRepository> newRepositories =
            Collections.singletonList(toRemoteRepository( repository ) );

        this.repositories =
            remoteRepositoryManager.aggregateRepositories( session, repositories, newRepositories, true );
    }

    @Override
    public ModelResolver newCopy()
    {
        return new BasicModelResolver31( this );
    }

    @Override
    public ModelSource resolveModel( String groupId, String artifactId, String version )
        throws UnresolvableModelException
    {
        Artifact pomArtifact = new DefaultArtifact( groupId, artifactId, "", "pom", version );

        try
        {
            ArtifactRequest request = new ArtifactRequest();
            request.setArtifact( pomArtifact );
            request.setRepositories( repositories );
            pomArtifact = resolver.resolveArtifact( session, request ).getArtifact();
        }
        catch ( ArtifactResolutionException e )
        {
            throw new UnresolvableModelException( e.getMessage(), groupId, artifactId, version, e );
        }

        File pomFile = pomArtifact.getFile();

        return new FileModelSource( pomFile );
    }

    // The following two methods are shamless plugs:
    // They are adapted form org.apache.maven.repository.internal.ArtifactDescriptorUtil

    private static RemoteRepository toRemoteRepository( Repository repository )
    {
        return
            new RemoteRepository.Builder( repository.getId(), repository.getLayout(), repository.getUrl())
                    .setSnapshotPolicy(toRepositoryPolicy( repository.getSnapshots()))
                    .setReleasePolicy(toRepositoryPolicy( repository.getReleases()))
                    .build();
    }

    private static RepositoryPolicy toRepositoryPolicy( org.apache.maven.model.RepositoryPolicy policy )
    {
        boolean enabled = true;
        String checksums = RepositoryPolicy.CHECKSUM_POLICY_WARN;
        String updates = RepositoryPolicy.UPDATE_POLICY_DAILY;

        if ( policy != null )
        {
            enabled = policy.isEnabled();
            if ( policy.getUpdatePolicy() != null )
            {
                updates = policy.getUpdatePolicy();
            }
            if ( policy.getChecksumPolicy() != null )
            {
                checksums = policy.getChecksumPolicy();
            }
        }

        return new RepositoryPolicy( enabled, updates, checksums );
    }

    public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    public void addRepository(Repository repository, boolean replace) throws InvalidRepositoryException {
        addRepository(repository);
    }
}
