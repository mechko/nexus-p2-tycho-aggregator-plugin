/**
 * Copyright (c) 2008-2011 Sonatype, Inc.
 *
 * All rights reserved. Includes the third-party code listed at http://www.sonatype.com/products/nexus/attributions.
 * Sonatype and Sonatype Nexus are trademarks of Sonatype, Inc. Apache Maven is a trademark of the Apache Foundation.
 * M2Eclipse is a trademark of the Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package com.sonatype.nexus.proxy.p2.its;

import org.sonatype.jettytestsuite.ServletServer;

public abstract class AbstractNexusProxyP2SecureIntegrationIT
    extends AbstractNexusProxyP2IntegrationIT
{
    protected AbstractNexusProxyP2SecureIntegrationIT()
    {
    }

    protected AbstractNexusProxyP2SecureIntegrationIT( String testRepositoryId )
    {
        super( testRepositoryId );
    }

    @Override
    public void startProxy()
        throws Exception
    {
        ServletServer server = (ServletServer) this.lookup( ServletServer.ROLE, "secure" );
        server.start();
    }

    @Override
    public void stopProxy()
        throws Exception
    {
        ServletServer server = (ServletServer) this.lookup( ServletServer.ROLE, "secure" );
        server.stop();
    }
}
