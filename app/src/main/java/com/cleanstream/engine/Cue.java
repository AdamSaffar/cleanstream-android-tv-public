package com.cleanstream.engine;

/** One subtitle cue in CONTENT time (ms). */
public class Cue {
    public final long start;
    public final long end;
    public final String text;

    public Cue(long start, long end, String text) {
        this.start = start;
        this.end = end;
        this.text = text;
    }
}
