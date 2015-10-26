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
package org.jboss.maven.extension.dependency;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.IOUtil;
import org.jboss.maven.extension.dependency.metainf.EffectivePomGenerator;
import org.jboss.maven.extension.dependency.metainf.MetaInfWriter;
import org.jboss.maven.extension.dependency.modelmodifier.ModelModifier;
import org.jboss.maven.extension.dependency.modelmodifier.SessionModifier;
import org.jboss.maven.extension.dependency.modelmodifier.propertyoverride.PropertyMappingOverrider;
import org.jboss.maven.extension.dependency.modelmodifier.versionoverride.DepVersionOverrider;
import org.jboss.maven.extension.dependency.modelmodifier.versionoverride.PluginVersionOverrider;
import org.jboss.maven.extension.dependency.resolver.EffectiveModelBuilder;
import org.jboss.maven.extension.dependency.resolver.EffectiveModelBuilder31;
import org.jboss.maven.extension.dependency.util.Log;
import org.jboss.maven.extension.dependency.util.MavenUtil;

/**
 * Main executor. Operates at the point defined by superclass as "afterProjectsRead", which is "after all MavenProject
 * instances have been created". This should allow access to the model(s) after they are built, but before they are
 * used.
 */
@Component( role = AbstractMavenLifecycleParticipant.class, hint = "dependencymanagement" )
public class DependencyManagementLifecycleParticipant
    extends AbstractMavenLifecycleParticipant
{
    @Requirement
    private Logger logger;

    private final List<ModelModifier> afterProjectsReadModifierList = new ArrayList<ModelModifier>();

    private final List<SessionModifier> afterSessionStartModifierList = new ArrayList<SessionModifier>();

    @Requirement
    private PlexusContainer plexus;

    @Requirement
    private ModelBuilder modelBuilder;

    private int sessionChangeCount = 0;

    /**
     * Load the build modifiers at instantiation time
     */
    public DependencyManagementLifecycleParticipant()
    {
        // Logger is not available yet
        System.out.println( "[INFO] Init Maven Dependency Management Extension " + loadProjectVersion() );

        afterProjectsReadModifierList.add( new DepVersionOverrider() );
        afterProjectsReadModifierList.add( new PluginVersionOverrider() );

        afterSessionStartModifierList.add( new PropertyMappingOverrider() );

    }

    /**
     * Get the version of the current project from the properties file
     *
     * @return The version of this project
     */
    private String loadProjectVersion()
    {
        InputStream in = null;
        Properties props = new Properties();
        try
        {
            in = getClass().getResourceAsStream( "project.properties" );
            props.load( in );
        }
        catch ( IOException e )
        {
            // Ignore the error if we can't get the version.
        }
        finally
        {
            IOUtil.close( in );
        }
        String version = props.getProperty( "project.version" );
        if (version == null)
        {
            version = "";
        }
        return version;
    }

    @Override
    public void afterSessionStart( MavenSession session )
        throws MavenExecutionException
    {
        Log.setLog( logger );

        try
        {
            if (getMavenVersion().compareTo("3.0.5") <= 0) {
                EffectiveModelBuilder.init( session, plexus, modelBuilder );
                logger.debug("using sonatype aether impl");
            } else {
                EffectiveModelBuilder31.init( session, plexus, modelBuilder );
                logger.debug("using eclipse aether impl");
            }
        }
        catch ( ComponentLookupException e )
        {
            logger.error( "EffectiveModelBuilder init could not look up plexus component: " + e );
        }
        catch ( PlexusContainerException e )
        {
            logger.error( "EffectiveModelBuilder init produced a plexus container error: " + e );
        }
        for ( SessionModifier currModifier : afterSessionStartModifierList )
        {
            boolean modelChanged = currModifier.updateSession( session );
            if ( modelChanged )
            {
                sessionChangeCount++;
            }
        }
    }

    private String getMavenVersion()
    {
        Properties props = new Properties();

        InputStream is = MavenUtil.class.getResourceAsStream( "/META-INF/maven/org.apache.maven/maven-core/pom.properties" );
        if ( is != null )
        {
            try
            {
                props.load( is );
            }
            catch ( IOException e )
            {
                logger.debug( "Failed to read Maven version", e );
            }

            IOUtil.close( is );
        }
        return props.getProperty( "version", "unknown-version" );

    }

    @Override
    public void afterProjectsRead( MavenSession session )
        throws MavenExecutionException
    {
        // The dependency management overrider needs to know which projects
        // are in the reactor, and therefore should not be overridden.
        StringBuilder reactorProjects = new StringBuilder();
        for ( MavenProject project : session.getProjects() )
        {
            reactorProjects.append( project.getGroupId() + ":" + project.getArtifactId() + "," );
        }
        System.setProperty( "reactorProjectGAs",  reactorProjects.toString() );

        // Apply model modifiers to the projects' models
        for ( MavenProject project : session.getProjects() )
        {
            logger.debug( "Checking project '" + project.getId() + "'" );
            int modelChangeCount = 0;

            Model currModel = project.getModel();

            // Run the modifiers against the built model
            for ( ModelModifier currModifier : afterProjectsReadModifierList )
            {
                boolean modelChanged = currModifier.updateModel( currModel );
                if ( modelChanged )
                {
                    modelChangeCount++;
                }
            }

            // If something changed, then it will be useful to output extra info
            if ( sessionChangeCount >=1 || modelChangeCount >= 1 )
            {
                logger.debug( "Session/Model changed at least once, writing informational files" );
                try
                {
                    MetaInfWriter.writeResource( currModel, new EffectivePomGenerator() );
                }
                catch ( IOException e )
                {
                    logger.error( "Could not write the effective POM of model '" + currModel.getId() + "' due to " + e );
                }
            }
        }

    }
}
