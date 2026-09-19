package com.railway.sandbox.model;

/**
 * Versioned, data-driven interlocking rule set. Different versions may relax
 * or tighten guards; this is exactly what the candidate-vs-approved
 * comparison visualises. Every boolean flips one guard or one invariant.
 */
public final class RuleSet {
    public String version = "1";
    public String note = "";

    /** A route may not be established while any of its sections is occupied. */
    public boolean requireSectionsFree = true;
    /** Conflicting routes (shared sections or lateral conflicts) are locked out. */
    public boolean enforceRouteMutex = true;
    /** Switch must already stand in the required position before the route locks. */
    public boolean requireSwitchPosition = true;
    /** Switching a point is forbidden while the point section is occupied or locked. */
    public boolean blockSwitchUnderMovement = true;
    /** Diverging routes additionally require lateral (flank) protection. */
    public boolean requireFlankProtection = true;
    /** A route may only be released after the train has fully left it. */
    public boolean releaseOnlyAfterClear = true;
    /** Sections must be released in entry-to-exit order during a release. */
    public boolean enforceReleaseOrder = true;
    /** A signal may only clear when its route is FULLY locked. */
    public boolean signalRequiresFullLock = true;
    /** Non-boolean tunable: max permitted shunt speed (km/h). Enables genuine
     *  three-way merge conflicts (two numeric edits of one parameter). */
    public int maxShuntSpeed = 25;
}
