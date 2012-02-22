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
package com.qualitype.nexus.plugins.tycho;

import org.sonatype.nexus.proxy.item.StorageItem;

import com.qualitype.nexus.plugins.tycho.P2TychoRepositoryAggregatorConfiguration;

public interface P2TychoRepositoryAggregator
{

    void addConfiguration( final P2TychoRepositoryAggregatorConfiguration configuration );

    void removeConfiguration( final P2TychoRepositoryAggregatorConfiguration configuration );

    P2TychoRepositoryAggregatorConfiguration getConfiguration( final String repositoryId );

    void updateP2Artifacts( StorageItem item );

    void removeP2Artifacts( StorageItem item );

    void updateP2Metadata( StorageItem item );

    void removeP2Metadata( StorageItem item );

    void scanAndRebuild( String repositoryId );

    void scanAndRebuild();

}
