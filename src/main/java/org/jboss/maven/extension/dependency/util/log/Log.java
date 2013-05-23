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
package org.jboss.maven.extension.dependency.util.log;

import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;

/**
 * Provides a way for classes in the local packages to perform central logging
 */
public class Log
{
    private static Logger logger;

    /**
     * Yields the central logger object.
     * 
     * @return A Logger object, never null.
     */
    public static Logger getLog()
    {
        // First access creates the logger (on-demand)
        if ( logger == null )
        {
            logger = new ConsoleLogger( Logger.LEVEL_INFO, "Plexus Console Logger" );
        }
        return logger;
    }

    /**
     * Sets the central logger object.
     */
    public static void setLog(Logger logger)
    {
        Log.logger = logger;
    }

    /**
     * Recursively log all causes in a Throwable chain
     * 
     * @param cause Will be null in the base case
     */
    public static void logAllCauses( Throwable cause )
    {
        if ( cause == null )
        {
            return;
        }
        getLog().error( "Cause: " + cause );
        logAllCauses( cause.getCause() );
    }
}
