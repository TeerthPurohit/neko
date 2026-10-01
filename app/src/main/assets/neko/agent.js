import "/assets/neko/model-viewer.min.js";

const neko = document.querySelector("#neko");
const halo = document.querySelector("#halo");

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
