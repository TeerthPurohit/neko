import "/assets/neko/model-viewer.min.js";

const neko = document.querySelector("#neko");
const halo = document.querySelector("#halo");

// Android WebView can resolve percentage/vh heights to zero for this local page
// even when innerHeight is correct. Give the original model a concrete viewport.
function resizeStage() {
  const height = `${Math.max(1, window.innerHeight)}px`;
  document.documentElement.style.height = height;
  document.body.style.height = height;
  neko.style.height = height;
}
window.addEventListener("resize", resizeStage);
resizeStage();

let reducedMotion = false;
let speaking = false;

function updateMotion() {
  halo.classList.toggle("speaking", speaking && !reducedMotion);
  neko.timeScale = speaking ? 1.12 : 0.82;

  if (document.hidden || reducedMotion) {
    neko.pause();
  } else {
    neko.play();
  }
}

window.nekoSetAgentState = (isSpeaking, shouldReduceMotion) => {
  speaking = Boolean(isSpeaking);
  reducedMotion = Boolean(shouldReduceMotion);
  updateMotion();
};

document.addEventListener("visibilitychange", updateMotion);
