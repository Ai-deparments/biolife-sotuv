/**
 * Reyting va motivatsiya: weekly rating of all agents, two kinds of money (sales % and conversation bonus),
 * missions (improvement advice), best cases, manager attention list.
 * Consumes domain events only; keeps its own read tables (rating_*). Never reads other modules' tables.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Rating")
package uz.biolife.rating;
