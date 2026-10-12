package org.telegram.messenger.nexo;

/** FORK MOD (Nexo): a file read from a content URI, ready to be sent to the Mac */
public class LocalFile {
    public final String name;
    public final String mime;
    public final byte[] bytes;

    public LocalFile(String name, String mime, byte[] bytes) {
        this.name = name;
        this.mime = mime;
        this.bytes = bytes;
    }
}
