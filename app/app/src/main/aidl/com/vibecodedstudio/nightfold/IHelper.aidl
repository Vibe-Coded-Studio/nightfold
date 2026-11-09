package com.vibecodedstudio.nightfold;

/** The Shizuku (shell user) helper that draws the effect on its own top layer. */
interface IHelper {
    void setEnabled(boolean on) = 1;
    String status() = 2;
    void innerDrawn() = 3;                          // unused since the helper draws itself; kept so codes stay stable
    void setFx(int fx, boolean slide) = 4;
    boolean rendering() = 5;                        // true = the helper draws the effect (app overlay not needed)
    void setTiming(int coverMs, int innerMs) = 6;   // opening play speed (the sliders)
    void startTraining(int opens) = 7;              // measure the next N cover-on opens
    String training() = 8;                          // "idle" | "k/N" | "done <coverMs>"
    String grantApp(String pkg) = 9;                // shell grants the app's other permissions (one Shizuku allow = all)
    void setFinish(int mode, int finMs) = 10;       // finish pass: 0 off, 1 A back, 2 B same way, 3 C middle out
    void setSoft(int blurTenths, int wobble) = 11;  // before the finish pass: blur 0-30 = 0.0-3.0, wobble 0-10
    boolean hingeFromApp(float angle, long tsNs) = 12; // public hinge sensor from the app; false = helper has its own feed
    boolean logFeed() = 13;                         // true once Samsung's hinge log gave a reading
    String captureNow() = 14;                       // capture the lit screen's home screen now ("inner"/"cover"/why not)
    boolean openApp() = 15;                         // bring Nightfold back to the front as the shell user (an app that's
                                                    // behind the home screen may not start itself: Android blocks it)
    void unlocked() = 16;                           // the phone was just unlocked (app hears USER_PRESENT): capture the
                                                    // cover's home screen soon if the cover is the screen in use
    void destroy() = 16777114;                      // Shizuku's reserved "destroy" transaction
}
