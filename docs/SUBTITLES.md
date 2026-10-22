# Subtitle ingestion

The active SrtParser validates complete SRT files, integer timestamps, increasing positive indices, nonempty text and positive intervals. VTT is converted by its supported internal converter. Invalid files fail the job instead of publishing partial content. Stable cue IDs include source and interval; duplicate intervals receive suffixes. Equal-time input order is persisted through ordinal.

See SrtParserTest and StrictSrtParserTest for edge cases, and README for the asynchronous job API.
