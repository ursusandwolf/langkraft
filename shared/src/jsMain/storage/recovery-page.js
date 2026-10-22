import { cloneWebProfile, exportWebProfile, importWebProfile, listWebRecoveryProfiles } from "./recovery.js";
import { selectedProfile } from "./profile.js";

const buttons = [...document.querySelectorAll("button")];
const status = document.getElementById("status");
const result = document.getElementById("result");
const reportView = document.getElementById("report");
document.getElementById("source").textContent = selectedProfile || "Основной профиль";

async function run(operation) {
  buttons.forEach(button => { button.disabled = true; });
  result.hidden = true;
  reportView.textContent = "";
  status.textContent = "Подготовка копии…";
  try {
    const report = await operation();
    const link = document.getElementById("open-copy");
    const url = new URL("./", location.href);
    url.searchParams.set("profile", report.profile);
    link.href = url.href;
    result.hidden = false;
    reportView.textContent = JSON.stringify(report, null, 2);
    status.textContent = "Копия готова. Проверьте библиотеку и аудио до входа в аккаунт.";
  } catch (error) {
    status.textContent = `Не удалось восстановить: ${error.message}`;
  } finally {
    await refreshProfiles();
    buttons.forEach(button => { button.disabled = false; });
  }
}

let inventoryGeneration = 0;
async function refreshProfiles() {
  const generation = ++inventoryGeneration;
  const list = document.getElementById("profiles");
  const message = document.getElementById("profiles-status");
  list.replaceChildren();
  try {
    const profiles = await listWebRecoveryProfiles();
    if (generation !== inventoryGeneration) return;
    message.textContent = `Найдено копий: ${profiles.length}. Наличие двух хранилищ не гарантирует исправность данных.`;
    for (const entry of profiles) {
      const item = document.createElement("li");
      const label = document.createElement("span");
      label.textContent = `${entry.profile}: SQLite ${entry.sqlite ? "есть" : "нет"}, аудио ${entry.audio ? "есть" : "нет"}. `;
      item.append(label);
      // Partial/failed targets are visible, but opening them may create missing stores.
      if (entry.sqlite && entry.audio) {
        for (const [path, text] of [["./", "Открыть профиль"], ["./recovery.html", "Восстановить из этой копии"]]) {
          const link = document.createElement("a");
          const url = new URL(path, location.href);
          url.searchParams.set("profile", entry.profile);
          link.href = url.href;
          link.textContent = text;
          item.append(link, document.createTextNode(" "));
        }
      }
      list.append(item);
    }
  } catch (error) {
    if (generation === inventoryGeneration) message.textContent = `Список недоступен: ${error.message}`;
  }
}

document.getElementById("refresh-profiles").onclick = refreshProfiles;
refreshProfiles();

document.getElementById("clone").onclick = () => run(() => cloneWebProfile(selectedProfile));
document.getElementById("export").onclick = () => run(async () => {
  const { blob, report } = await exportWebProfile(selectedProfile);
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "langkraft-recovery.json";
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
  return report;
});
document.getElementById("import").onclick = () => run(async () => {
  const file = document.getElementById("archive").files[0];
  if (!file) throw new Error("Выберите архив восстановления");
  return importWebProfile(file);
});
