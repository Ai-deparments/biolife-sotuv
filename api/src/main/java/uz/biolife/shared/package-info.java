/**
 * Cross-cutting code every module may use: event contracts, time rules, request identity, API error shape.
 * The only persistence here is the advisory-lock helper; no tables. Marked as a shared module in {@code BiolifeApplication}.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package uz.biolife.shared;
