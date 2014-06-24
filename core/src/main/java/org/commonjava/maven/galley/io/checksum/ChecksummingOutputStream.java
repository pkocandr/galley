package org.commonjava.maven.galley.io.checksum;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

import org.commonjava.maven.galley.model.Transfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChecksummingOutputStream
    extends FilterOutputStream
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final Set<AbstractChecksumGenerator> checksums;

    private final Transfer transfer;

    public ChecksummingOutputStream( final Set<AbstractChecksumGeneratorFactory<?>> checksumFactories,
                                     final OutputStream stream, final Transfer transfer )
        throws IOException
    {
        super( stream );
        this.transfer = transfer;
        checksums = new HashSet<>();
        for ( final AbstractChecksumGeneratorFactory<?> factory : checksumFactories )
        {
            checksums.add( factory.createGenerator( transfer ) );
        }
    }

    @Override
    public void close()
        throws IOException
    {
        super.close();

        logger.info( "Wrote main file: {}. Now, writing checksums.", transfer.getDetachedFile() );
        for ( final AbstractChecksumGenerator checksum : checksums )
        {
            checksum.write();
        }
    }

    @Override
    public void write( final int data )
        throws IOException
    {
        super.write( data );
        for ( final AbstractChecksumGenerator checksum : checksums )
        {
            checksum.update( (byte) data );
        }
    }

}