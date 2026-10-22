// Empty profile keeps the historical database names and links unchanged.
export function profileNames(profile = "") {
  if (profile !== "" && !/^recovered-[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(profile)) {
    throw new Error("Invalid recovery profile identifier");
  }
  const suffix = profile ? `-${profile}` : "";
  // wa-sqlite 1.0's bundled VFS limits paths (including journal suffixes) to 64 bytes.
  const file = profile ? `r-${profile.slice(10).replaceAll("-", "")}.db` : "langkraft.db";
  return { sqlite: `langkraft-sqlite${suffix}`, audio: `langkraft-audio${suffix}`, file };
}

export const selectedProfile = new URL(globalThis.location.href).searchParams.get("profile") || "";
profileNames(selectedProfile);
