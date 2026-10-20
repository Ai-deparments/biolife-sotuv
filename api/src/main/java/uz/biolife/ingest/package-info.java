/**
 * TEMPORARY fact intake. Until visit / analysis / media / territory modules exist, other services
 * (and the local seed) post facts here and they are re-published as domain events.
 * Deleted once the real publishers land; rating and reporting do not depend on this package.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Ingest (temporary)")
package uz.biolife.ingest;
