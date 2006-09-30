package org.nsdl.mptstore.query;

/**
 * Signals a query-related error.
 *
 * @author cwilper@cs.cornell.edu
 */
public class QueryException extends Exception {

    /**
     * Construct a QueryException with a detail message.
     *
     * @param message the detail message.
     */
    public QueryException(String message) {
        super(message);
    }

    /**
     * Construct a QueryException with a detail message and a cause.
     *
     * @param message the detail message.
     * @param cause the cause.
     */
    public QueryException(String message, Throwable cause) {
        super(message, cause);
    }

}
