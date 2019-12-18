package com.salesforce;

import io.micrometer.core.instrument.Tag;

/**
 * This class implements io.micrometer.core.instrument.Tag interface
 * and provides a hard coded order. This is useful because
 * otherwise micrometer implementation orders the tags based on the keys
 * and this leads to ugly metric naming.
 */
public class CustomOrderedTag implements Tag {
    private final String key;
    private final String value;
    private final int relativeOrder;

    private CustomOrderedTag(final String key, final String value, final int relativeOrder) {
        this.key = key;
        this.value = value;
        this.relativeOrder = relativeOrder;
    }
    static public Tag of(String key, String value, int relativeOrder) {
        return new CustomOrderedTag(key, value, relativeOrder);
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public int compareTo(Tag o) {
        if (o instanceof CustomOrderedTag) {
            return Integer.compare(this.relativeOrder, ((CustomOrderedTag) o).relativeOrder);
        } else {
            return getKey().compareTo(o.getKey());
        }
    }
}
