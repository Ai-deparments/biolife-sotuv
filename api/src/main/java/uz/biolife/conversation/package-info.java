/**
 * Conversations: agents record the talk in the shop, an external worker turns speech into text (Gemini) and scores it
 * against the rubric (Claude); this module stores the recording and the result, links it to the Linko visit by time,
 * and publishes {@code AnalysisCompleted} so rating and reporting pick the score up. Own tables: conv_*.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Conversations")
package uz.biolife.conversation;
