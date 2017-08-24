package no.difi.commons.asic;

import no.difi.commons.asic.annotation.Processor;
import no.difi.commons.asic.api.AsicWriter;
import no.difi.commons.asic.api.AsicWriterLayer;
import no.difi.commons.asic.api.EncryptionFilter;
import no.difi.commons.asic.builder.Properties;
import no.difi.commons.asic.lang.AsicException;
import no.difi.commons.asic.model.Container;
import no.difi.commons.asic.model.DataObject;
import no.difi.commons.asic.model.MimeType;
import no.difi.commons.asic.security.MultiMessageDigest;
import no.difi.commons.asic.util.MimeTypeUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/**
 * @author erlend
 */
class AsicWriterImpl implements AsicWriter {

    private final Properties properties;

    private final OutputStream outputStream;

    private final AsicWriterLayer asicWriterLayer;

    private final boolean closeStreamOnClose;

    private final Container container = new Container(Container.Mode.WRITER);

    private boolean signed = false;

    private boolean encrypt = false;

    protected AsicWriterImpl(Properties properties, OutputStream outputStream, boolean closeStreamOnClose)
            throws IOException {
        this.properties = properties;
        this.outputStream = outputStream;
        this.closeStreamOnClose = closeStreamOnClose;

        MultiMessageDigest messageDigest =
                new MultiMessageDigest(properties.get(Asic.SIGNATURE_OBJECT_ALGORITHM));
        this.asicWriterLayer = new AsicWriterLayerImpl(outputStream, messageDigest, container);

        performProcessors(Processor.State.INITIAL);
    }

    @Override
    public OutputStream add(String filename, MimeType mimeType) throws IOException {
        if (signed)
            throw new AsicException("Adding content to container after signing container is not supported.");

        if (filename.toUpperCase().startsWith("META-INF/"))
            throw new AsicException("Adding files to META-INF is not allowed.");

        if (encrypt) {
            encrypt = false;

            EncryptionFilter encryptionFilter = properties.get(Asic.ENCRYPTION_FILTER);

            return encryptionFilter.createFilter(asicWriterLayer.addContent(
                    DataObject.Type.DATA,
                    encryptionFilter.filename(filename),
                    mimeType != null ? mimeType : MimeTypeUtil.detect(filename)), properties);
        } else {
            return asicWriterLayer.addContent(
                    DataObject.Type.DATA,
                    filename,
                    mimeType != null ? mimeType : MimeTypeUtil.detect(filename));
        }
    }

    @Override
    public AsicWriter encryptNext() {
        encrypt = true;

        return this;
    }

    @Override
    public AsicWriter setRootFile(String filename) throws AsicException {
        if (!properties.get(Asic.SIGNATURE_CREATOR).supportsRootFile())
            throw new AsicException("Root file is not supported with current configuration.");

        container.setRootFile(filename);

        return this;
    }

    @Override
    public AsicWriter sign() throws IOException {
        performProcessors(Processor.State.BEFORE_SIGNATURE);

        properties.get(Asic.SIGNATURE_CREATOR)
                .create(asicWriterLayer, container, properties);

        signed = true;

        performProcessors(Processor.State.AFTER_SIGNATURE);

        return this;
    }

    @Override
    public void close() throws IOException {
        if (!signed)
            throw new IOException("Unsigned ASiC-E is not possible.");

        asicWriterLayer.close();

        if (closeStreamOnClose)
            outputStream.close();
    }

    private void performProcessors(Processor.State state) throws IOException {
        try {
            properties.get(Asic.WRITER_PROCESSORS).stream()
                    .filter(p -> p.getState().equals(state))
                    .forEach(p -> {
                        try {
                            p.perform(asicWriterLayer, container, properties);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
