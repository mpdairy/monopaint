package io.github.mpdairy.monopaint;

public final class RotationSuggestionChecks {
    public static void main(String[] args) {
        RotationSuggestion suggestion = new RotationSuggestion();
        for (int quarter=0;quarter<4;quarter++) {
            suggestion.reset();
            int angle=quarter*90, current=(quarter+1)%4;
            check(suggestion.update(angle,current,0)==-1,"No immediate rotation suggestion");
            check(suggestion.update(angle+10,current,649)==-1,"Hold is required despite nearby samples");
            check(suggestion.update(angle,current,650)==quarter,"Stable cardinal position is suggested");
            check(suggestion.update(angle,quarter,651)==-1,"Accepted position clears suggestion");
        }
        suggestion.reset();
        suggestion.update(270,0,0);
        check(suggestion.update(-1,0,800)==-1,"Flat/unknown cancels pending suggestion");
        check(suggestion.update(270,0,900)==-1,"Returning from flat needs a new hold");
        check(suggestion.update(270,0,1550)==3,"Landscape returns after hold");
        check(suggestion.update(230,0,1551)==-1,"Diagonal clears visible suggestion");
        suggestion.update(90,0,2000);
        check(suggestion.update(270,0,2650)==-1,"Opposite direction starts a fresh hold");
        check(suggestion.update(270,0,3300)==3,"Opposite landscape settles");
        suggestion.reset();
        suggestion.update(359,3,0);
        check(suggestion.update(1,3,650)==0,"Portrait wraps cleanly across zero");
        check(suggestion.update(360,3,651)==-1,"Invalid angle clears suggestion");
        System.out.println("PASS: orientation dwell, both landscape directions, portrait return, wraparound, diagonal and flat rejection");
    }
    private static void check(boolean value,String message) {
        if (!value) throw new AssertionError(message);
    }
}
