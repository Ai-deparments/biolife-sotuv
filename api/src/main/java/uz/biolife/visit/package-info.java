/**
 * Visit list for the manager panel: every field visit as the source recorded it (store, time, distance, plan,
 * photos, orders), per-branch coverage and photo checks. Event consumer with its own read tables (visit_*).
 * Owns no money totals: sales stay in reporting, so the two never disagree.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Visit list")
package uz.biolife.visit;
