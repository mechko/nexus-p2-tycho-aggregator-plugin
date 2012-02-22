/**
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2012 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package com.qualitype.nexus.plugins.tycho.internal.tasks;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.scheduling.AbstractNexusRepositoriesTask;
import org.sonatype.scheduling.SchedulerTask;

import com.qualitype.nexus.plugins.tycho.P2TychoRepositoryAggregator;

@Named( P2TychoRepositoryAggregatorTaskDescriptor.ID )
public class P2TychoRepositoryAggregatorTask
    extends AbstractNexusRepositoriesTask<Object>
    implements SchedulerTask<Object>
{

    private final P2TychoRepositoryAggregator p2RepositoryAggregator;

    @Inject
    P2TychoRepositoryAggregatorTask( final P2TychoRepositoryAggregator p2RepositoryAggregator )
    {
        this.p2RepositoryAggregator = p2RepositoryAggregator;
    }

    @Override
    protected String getRepositoryFieldId()
    {
        return P2TychoRepositoryAggregatorTaskDescriptor.REPO_OR_GROUP_FIELD_ID;
    }

    @Override
    protected String getAction()
    {
        return "REBUILD";
    }

    @Override
    protected String getMessage()
    {
        if ( getRepositoryId() != null )
        {
            return String.format( "Rebuild p2 repository on repository [%s] from root path and bellow",
                getRepositoryId() );
        }
        else
        {
            return "Rebuild p2 repository for all repositories (with a P2 Repository Generator Capability enabled)";
        }
    }

    @Override
    protected Object doRun()
        throws Exception
    {
        final String repositoryId = getRepositoryId();
        if ( repositoryId != null )
        {
            p2RepositoryAggregator.scanAndRebuild( repositoryId );
        }
        else
        {
            p2RepositoryAggregator.scanAndRebuild();
        }

        return null;
    }

}
