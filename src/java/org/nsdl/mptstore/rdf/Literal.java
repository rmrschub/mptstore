package org.nsdl.mptstore.rdf;


import java.text.ParseException;

import org.nsdl.mptstore.util.NTriplesUtil;

/**
 * An RDF literal.
 *
 * A literal has a lexical component and optionally has either a language
 * tag or a datatype (indicated by URIReference).
 * 
 * @author cwilper@cs.cornell.edu
 * @see <a href="http://www.w3.org/TR/rdf-concepts/#section-Graph-Literal">
 *      RDF Concepts and Abstract Syntax, Section 6.5</a>
 */
public class Literal implements ObjectNode {

    /**
     * Maximum characters for a language code subtag.
     */
    private static final int SUBTAG_MAXLEN = 8;

    /**
     * The lexical value of this literal.
     */
    private String _value;

    /**
     * The language tag of this literal, if any.
     */
    private String _language;

    /**
     * The datatype of this literal, if any.
     */
    private URIReference _datatype;

    /**
     * Construct a plain literal without a language tag.
     *
     * @param value The lexical value.
     */
    public Literal(final String value) {
        _value = value;
    }

    /**
     * Construct a plain literal with a language tag.
     * <p>
     *   The language tag must be of the following form:
     *   <pre>
     *     Language-Tag = Primary-subtag *( "-" Subtag )
     *     Primary-subtag = 1*8ALPHA
     *     Subtag = 1*8(ALPHA / DIGIT)
     *   </pre>
     * </p>
     * <p>
     *   As per "RDF Concepts and Abstract Syntax", the
     *   language tag will be normalized to lowercase.
     * </p>
     *
     * @param value The lexical value.
     * @param language The language tag.
     * @throws ParseException if the language is syntactically
     *         invalid according to RFC3066.
     */
    public Literal(final String value, 
                   final String language) 
            throws ParseException {
        _value = value;
        if (language != null) {
            if (language.length() == 0) {
                throw new ParseException("Language tag must not be empty", 0);
            }
            if (language.indexOf(" ") != -1) {
                throw new ParseException("Space character not allowed in "
                        + "language tag", language.indexOf(" "));
            }

            String[] parts = language.split("-");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].length() < 1 
                        || parts[i].length() > SUBTAG_MAXLEN) {
                    throw new ParseException("Language subtags must be "
                            + "1-" + SUBTAG_MAXLEN + " characters long", 0);
                }
                for (int j = 0; j < parts[i].length(); j++) {
                    char c = parts[i].charAt(j);
                    if (!((c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9' && i > 0))) {
                        throw new ParseException("Language subtag cannot "
                                + "contain character '" + c + "'", 0);
                    }
                }
            }

            _language = language.toLowerCase();
        }
    }

    /**
     * Construct a typed literal.
     *
     * @param value The lexical value.
     * @param datatype The datatype.
     */
    public Literal(final String value, 
                   final URIReference datatype) {
        _value = value;
        _datatype = datatype;
    }

    /**
     * Get the language tag, if any.
     *
     * @return The language tag, or <code>null</code> if none.
     */
    public String getLanguage() {
        return _language;
    }

    /**
     * Get the datatype, if any.
     *
     * @return The datatype, or <code>null</code> if none.
     */
    public URIReference getDatatype() {
        return _datatype;
    }

    /** {@inheritDoc} */
    public String getValue() {
        return _value;
    }

    /** {@inheritDoc} */
    public String toString() {
        StringBuffer out = new StringBuffer();
        out.append('"');
        out.append(NTriplesUtil.escapeLiteralValue(_value));
        out.append('"');
        if (_language != null) {
            out.append("@" + _language);
        } else if (_datatype != null) {
            out.append("^^" + _datatype.toString());
        }
        return out.toString();
    }

    /** {@inheritDoc} */
    public boolean equals(final Object obj) {
        if (obj != null && obj instanceof Literal) {
            Literal lit = (Literal) obj;
            if (_language != null) {
                return _language.equals(lit.getLanguage())
                    && _value.equals(lit.getValue());
            } else if (_datatype != null) {
                return _datatype.equals(lit.getDatatype())
                    && _value.equals(lit.getValue());
            } else {
                return lit.getLanguage() == null
                    && lit.getDatatype() == null
                    && _value.equals(lit.getValue());
            }
        } else {
            return false;
        }
    }

    /** {@inheritDoc} */
    public int hashCode() {
        return _value.hashCode();
    }

}
