package com.solesonic.service.interest;

/**
 * What one news tick did, in counts only. Nothing here may ever carry a topic, a query, a headline or
 * a URL.
 */
public final class NewsRun {
    private int delivered;
    private int quiet;
    private int discarded;
    private int failed;

    void delivered() {
        delivered++;
    }

    void quiet() {
        quiet++;
    }

    void discarded() {
        discarded++;
    }

    void failed() {
        failed++;
    }

    public int deliveredCount() {
        return delivered;
    }

    public int quietCount() {
        return quiet;
    }

    public int discardedCount() {
        return discarded;
    }

    public int failedCount() {
        return failed;
    }

    @Override
    public String toString() {
        return "delivered=" + delivered + " quiet=" + quiet + " discarded=" + discarded + " failed=" + failed;
    }
}
