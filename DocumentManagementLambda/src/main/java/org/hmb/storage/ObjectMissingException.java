package org.hmb.storage;

/** The S3 object a database row points at does not exist. Carries no key, so it is safe to log. */
public class ObjectMissingException extends RuntimeException {

    public ObjectMissingException() {
        super("The S3 object does not exist");
    }
}
