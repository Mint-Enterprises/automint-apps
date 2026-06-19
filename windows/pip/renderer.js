const api = window.pip;

document
  .getElementById("btn-close")
  .addEventListener("click", () => api.close());
document
  .getElementById("btn-expand")
  .addEventListener("click", () => api.openMain());

api.onWindowFocusChanged((focused) => {
  if (focused) {
    document.body.classList.add("window-focused");
  } else {
    document.body.classList.remove("window-focused");
  }
});
