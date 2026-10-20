/**
 * Data sources: when each upstream source (the Kuzatuv bridge, Linko visits, RITM orders and photos) last delivered,
 * so the panel can say how fresh its numbers are. Event consumer with one table (source_status).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Data sources")
package uz.biolife.sources;
