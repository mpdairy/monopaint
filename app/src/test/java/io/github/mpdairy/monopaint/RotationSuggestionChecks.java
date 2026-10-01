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
        suggestion.reset();suggestion.update(90,0,0);
        check(suggestion.update(90,0,650)==1,"Fresh orientation offers prompt");
        check(suggestion.update(90,0,5649)==1,"Prompt remains for five seconds");
        check(suggestion.update(90,0,5650)==-1,"Prompt expires");
        check(suggestion.update(91,0,9000)==-1,"Steady orientation never reopens expired prompt");
        check(suggestion.shake(90,0,10000)==1,"Shake recalls pending rotation");
        check(suggestion.update(90,0,15000)==-1,"Recalled prompt also expires");
        suggestion.dismiss();check(suggestion.update(90,0,16000)==-1,"Outside dismissal stays dismissed");
        check(suggestion.shake(90,0,17000)==1,"Shake recalls dismissed prompt");
        check(suggestion.shake(0,0,18000)==-1,"No rotation offered for current app orientation");
        suggestion.update(270,0,19000);
        check(suggestion.update(270,0,19650)==3,"Fresh direction offers a new prompt");
        check(suggestion.shake(-1,0,20000)==-1,"Flat tablet cannot suggest a rotation even after shake");
        System.out.println("PASS: orientation dwell, both landscape directions, portrait return, wraparound, diagonal and flat rejection");
    }
    private static void check(boolean value,String message) {
        if (!value) throw new AssertionError(message);
    }
}
