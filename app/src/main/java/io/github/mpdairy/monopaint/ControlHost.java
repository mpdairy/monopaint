package io.github.mpdairy.monopaint;

/** What the app's custom toolbar and header controls need from the screen that hosts them. */
interface ControlHost {
    /** True while a load, save or long fill owns the document; controls ignore presses. */
    boolean busy();
    /** True while a page action is queued; page buttons ignore presses. */
    boolean pageActionPending();
    /** Degrees the header strip is turned. Upright icons counter-rotate by this amount. */
    int toolbarTurn();
    /** Fast e-ink presentation for small control changes. */
    SelectionFeedback selectionFeedback();
}
