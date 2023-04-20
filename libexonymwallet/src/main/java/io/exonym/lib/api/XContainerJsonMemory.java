package io.exonym.lib.api;

import io.exonym.lib.pojo.NetworkMapItemAdvocate;
import io.exonym.lib.pojo.XContainerSchema;
import io.exonym.lib.standard.PassStore;
import io.exonym.lib.wallet.ExonymOwner;

import javax.crypto.Cipher;
import java.nio.file.Path;
import java.util.logging.Logger;

public class XContainerJsonMemory extends XContainerJSON {

    private final static Logger logger = Logger.getLogger(XContainerJsonMemory.class.getName());

    public XContainerJsonMemory() throws Exception {
        super("blank");
    }

    @Override
    protected void commitSchema() throws Exception {
        logger.fine("In memory container");
    }

    @Override
    protected XContainerSchema init(boolean create) throws Exception {
        return new XContainerSchema();
    }

    @Override
    public synchronized void saveLocalResource(Object resource) throws Exception {
        super.saveLocalResource(resource);
    }

    @Override
    public synchronized void saveLocalResource(Object resource, boolean overwrite) throws Exception {
        super.saveLocalResource(resource, overwrite);
    }

    public static void main(String[] args) throws Exception {
        XContainerJsonMemory x = new XContainerJsonMemory();
        ExonymOwner owner = new ExonymOwner(x);

        PassStore store = new PassStore("password", false);
        owner.openContainer(store);
        owner.setupContainerSecret(store.getEncrypt(), store.getDecipher());


        NetworkMapMemory m = NetworkMapMemory.getInstance();
        NetworkMapItemAdvocate nmia = m.nmiForSybilTestNet();

        logger.info("nmia=" + nmia.getNodeUID());

    }
}
