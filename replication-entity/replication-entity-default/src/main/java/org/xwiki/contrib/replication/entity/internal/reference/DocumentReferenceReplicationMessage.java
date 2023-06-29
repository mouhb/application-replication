/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.replication.entity.internal.reference;

import java.util.Collection;
import java.util.Map;

import javax.inject.Inject;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.replication.InvalidReplicationMessageException;
import org.xwiki.contrib.replication.ReplicationMessageReader;
import org.xwiki.contrib.replication.ReplicationReceiverMessage;
import org.xwiki.contrib.replication.entity.DocumentReplicationMessageReader;
import org.xwiki.contrib.replication.entity.internal.AbstractNoContentDocumentReplicationMessage;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.user.UserReference;

/**
 * @version $Id$
 */
@Component(roles = DocumentReferenceReplicationMessage.class)
public class DocumentReferenceReplicationMessage extends AbstractNoContentDocumentReplicationMessage
{
    @Inject
    private DocumentReplicationMessageReader documentMessageTool;

    @Inject
    private ReplicationMessageReader messageReader;

    @Override
    public String getType()
    {
        return TYPE_DOCUMENT_REFERENCE;
    }

    /**
     * @param documentReference the reference of the document to replicate
     * @param creatorReference the reference of the creator of the document
     * @param receivers the instances which are supposed to handler the message
     * @param extraMetadata custom metadata to add to the message
     * @since 1.1
     */
    public void initialize(DocumentReference documentReference, UserReference creatorReference,
        Collection<String> receivers, Map<String, Collection<String>> extraMetadata)
    {
        super.initialize(documentReference, receivers, extraMetadata);

        putCustomMetadata(METADATA_ENTITY_CREATOR, creatorReference);
    }

    /**
     * @param message the document update message to convert
     * @throws InvalidReplicationMessageException when the document update message is invalid
     */
    public void initialize(ReplicationReceiverMessage message) throws InvalidReplicationMessageException
    {
        initialize(this.documentMessageTool.getDocumentReference(message),
            this.messageReader.getMetadata(message, METADATA_ENTITY_CREATOR, true, UserReference.class),
            message.getReceivers(), message.getCustomMetadata());

        // Relay the source information
        this.id = message.getId();
        this.source = message.getSource();
        this.date = message.getDate();
    }
}
