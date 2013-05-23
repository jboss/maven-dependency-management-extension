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
package org.jboss.maven.extension.dependency.metainf;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.security.SecureRandom;

import org.apache.maven.model.Model;
import org.apache.maven.model.Resource;
import org.codehaus.plexus.logging.Logger;
import org.jboss.maven.extension.dependency.metainf.generator.MetaInfGenerator;
import org.jboss.maven.extension.dependency.util.log.Log;

/**
 * This class writes out metainf resources to be included with the jar at META-INF/maven/group/project/
 */
public class MetaInfWriter
{
    /**
     * A unique name to use for a prefix in temp output
     */
    private static String OUTPUT_DIR_PREFIX = "mvndepext";

    /**
     * Write the POM formatted model to the filesystem, and add it to the model build resources
     * 
     * @param model The model to convert to a POM, and to add the POM to
     * @throws IOException If there is a problem generating or writing the POM
     */
    public static void writeResource( Model model, MetaInfGenerator generator )
        throws IOException
    {
        // Paths
        String projectArtifactID = model.getArtifactId();
        String projectGroupID = model.getGroupId();
        String outputPath = TempDirectory.generateDir( OUTPUT_DIR_PREFIX ).toString();
        String groupPath = outputPath + File.separator + projectGroupID;
        String artifactPath = groupPath + File.separator + projectArtifactID;

        // Generator info
        String desc = generator.getDescription();
        String fileName = generator.getDescription().replace( " ", "-" );
        String fileExt = generator.getDesiredFileExtension();

        // File ref
        File outputFile = new File( artifactPath + File.separator + fileName + "." + fileExt );

        // Get content
        String content;
        try
        {
            content = generator.generateContent( model );
        }
        catch ( IOException e )
        {
            throw new IOException( "Couldn't generate " + desc + " from internal model", e );
        }

        // Write content to file
        try
        {
            writeStringToFile( content, outputFile );
        }
        catch ( IOException e )
        {
            throw new IOException( "Couldn't write " + desc, e );
        }

        // Add outputPath directory tree to model build resources
        Resource newResource = new Resource();
        newResource.setDirectory( outputPath );
        newResource.setTargetPath( "META-INF/maven" );

        model.getBuild().addResource( newResource );

        // Done
        Log.getLog().debug( desc + " written and included for '" + projectGroupID + ":" + projectArtifactID + "'" );
    }

    /**
     * Write the entirety of a string to a file, replacing any existing contents.
     * 
     * @param content String to use as the entire contents for the file
     * @param file File location to write or overwrite. Must not be a directory.
     * @throws IOException If the file exists and is a directory, or if the file does not exist but cannot be created,
     *             or cannot be opened for any other reason.
     */
    private static void writeStringToFile( String content, File file )
        throws IOException
    {
        if ( file.isDirectory() )
        {
            throw new IOException( "File '" + file + "' is a directory" );
        }

        File dirname = file.getParentFile();
        dirname.mkdirs();

        FileWriter writer = null;
        try
        {
            writer = new FileWriter( file );
            writer.write( content );
        }
        finally
        {
            if ( writer != null )
            {
                writer.close();
            }
        }
    }

    private static class TempDirectory
    {
        private static final File tmpdir = new File( System.getProperty( "java.io.tmpdir" ) );

        private static final SecureRandom random = new SecureRandom();

        /**
         * Create a random directory in the temp directory using prefix as the start of the new directory name.
         * 
         * @param prefix The start String of the random directory basename
         * @return File representing the new directory
         */
        static File generateDir( String prefix )
        {
            long suffix = random.nextLong();
            if ( suffix == Long.MIN_VALUE )
            {
                // Long.MIN_VALUE will not be changed by Math.abs
                suffix = 0;
            }
            else
            {
                suffix = Math.abs( suffix );
            }
            return new File( tmpdir, prefix + String.valueOf( suffix ) );
        }
    }
}
