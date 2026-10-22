// A separate entry avoids running Compose/startup cleanup on the source profile.
config.entry.recovery = [require.resolve("@langkraft/web-storage/recovery-page")];
