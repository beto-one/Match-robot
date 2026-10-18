#include <SoftwareSerial.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

SoftwareSerial bt(10, 11);  // RX, TX
Adafruit_SSD1306 oled(128, 64, &Wire, -1);

const int BTN = 4;  // optional talk button (pin 4 <-> GND)
const int BUZ = 8;  // optional buzzer (+ on pin 8, - on GND)

#define COLS 21
#define ROWS 8
#define MAXCH 160
#define MAXL 24

enum Mode { FACE_IDLE, FACE_LISTEN, FACE_THINK, TEXT };
Mode mode = FACE_IDLE;

char rx[MAXCH + 8];
int rxLen = 0;
char ans[MAXCH + 1];
int ansLen = 0;
uint8_t ls[MAXL], le[MAXL];
int nLines = 0;
int typed = 0;
int topLine = 0;
bool scrolling = false;
unsigned long tType = 0, tScroll = 0, tAnim = 0, tDone = 0;

// ---------- Face (Emo style with headphones) ----------
void drawHeadphones() {
  oled.drawFastHLine(11, 0, 106, WHITE);
  oled.drawFastHLine(11, 1, 106, WHITE);
  oled.drawCircleHelper(11, 11, 11, 1, WHITE);
  oled.drawCircleHelper(116, 11, 11, 2, WHITE);
  oled.drawFastVLine(0, 11, 14, WHITE);
  oled.drawFastVLine(127, 11, 14, WHITE);
  oled.fillRoundRect(0, 24, 9, 20, 3, WHITE);
  oled.fillRoundRect(119, 24, 9, 20, 3, WHITE);
}

void drawEyes(int h, int dx) {
  oled.fillRoundRect(28 + dx, 28 - h / 2, 24, h, 8, WHITE);
  oled.fillRoundRect(76 + dx, 28 - h / 2, 24, h, 8, WHITE);
}

void drawSmile() {
  for (int x = -9; x <= 9; x++) {
    int y = 49 + (x * x) / 18;
    oled.drawPixel(64 + x, y, WHITE);
    oled.drawPixel(64 + x, y + 1, WHITE);
  }
}

void loopFace() {
  unsigned long now = millis();
  if (now - tAnim < 60) return;
  tAnim = now;
  oled.clearDisplay();
  drawHeadphones();
  if (mode == FACE_IDLE) {
    int eh = 26, dx = 0;
    if (now % 3600 < 160) eh = 4;
    int lk = (now / 1800) % 6;
    if (lk == 4) dx = -5;
    if (lk == 5) dx = 5;
    drawEyes(eh, dx);
    drawSmile();
  } else if (mode == FACE_LISTEN) {
    drawEyes(34, 0);
    oled.fillCircle(64, 53, 4, WHITE);
  } else {  // FACE_THINK
    int dx = ((now / 450) % 2) ? 5 : -5;
    drawEyes(20, dx);
    int n = (now / 300) % 4;
    for (int i = 0; i < n; i++) oled.fillCircle(100 + i * 7, 54, 2, WHITE);
  }
  oled.display();
}

// ---------- Text (typewriter + scroll) ----------
void wrapText() {
  nLines = 0;
  int pos = 0;
  while (pos < ansLen && nLines < MAXL) {
    while (pos < ansLen && ans[pos] == ' ') pos++;
    if (pos >= ansLen) break;
    int end = pos + COLS;
    if (end >= ansLen) {
      end = ansLen;
    } else {
      int b = end;
      while (b > pos && ans[b] != ' ') b--;
      if (b > pos) end = b;
    }
    ls[nLines] = pos;
    le[nLines] = end;
    nLines++;
    pos = end;
  }
}

void drawText() {
  oled.clearDisplay();
  int lastLine = 0;
  for (int i = 0; i < nLines; i++) {
    if (ls[i] < typed) lastLine = i;
  }
  int top = scrolling ? topLine : max(0, lastLine - ROWS + 1);
  for (int r = 0; r < ROWS; r++) {
    int li = top + r;
    if (li >= nLines) break;
    oled.setCursor(0, r * 8);
    int e = le[li];
    if (e > typed) e = typed;
    for (int k = ls[li]; k < e; k++) oled.write(ans[k]);
  }
  if (nLines > ROWS) {
    int barH = 64 * ROWS / nLines;
    int barY = (64 - barH) * top / (nLines - ROWS);
    oled.drawFastVLine(127, barY, barH, WHITE);
  }
  oled.display();
}

void startText(const char* s) {
  strncpy(ans, s, MAXCH);
  ans[MAXCH] = 0;
  ansLen = strlen(ans);
  wrapText();
  typed = 0;
  topLine = 0;
  scrolling = false;
  mode = TEXT;
  tType = millis();
  tone(BUZ, 1800, 60);
  drawText();
}

void loopText() {
  unsigned long now = millis();
  if (typed < ansLen) {
    if (now - tType >= 45) {
      tType = now;
      typed++;
      drawText();
      if (typed == ansLen) tDone = now;
    }
  } else if (nLines > ROWS) {
    if (!scrolling) {
      if (now - tDone > 1800) {
        scrolling = true;
        topLine = 0;
        tScroll = now;
        drawText();
      }
    } else if (topLine < nLines - ROWS && now - tScroll > 1500) {
      tScroll = now;
      topLine++;
      drawText();
    }
  }
}

// ---------- Bluetooth commands ----------
// S:LISTEN  -> wake word heard, show face
// S:THINK   -> thinking face
// S:IDLE    -> calm face
// A:<text>  -> type the answer (stays until next S:LISTEN / S:THINK)
void handleLine() {
  rx[rxLen] = 0;
  if (rxLen > 2 && rx[1] == ':') {
    if (rx[0] == 'S') {
      if (strcmp(rx + 2, "LISTEN") == 0) {
        mode = FACE_LISTEN;
        tone(BUZ, 1200, 60);
      } else if (strcmp(rx + 2, "THINK") == 0) {
        mode = FACE_THINK;
      } else if (strcmp(rx + 2, "IDLE") == 0) {
        mode = FACE_IDLE;
      }
    } else if (rx[0] == 'A') {
      startText(rx + 2);
    }
  }
}

void setup() {
  pinMode(BTN, INPUT_PULLUP);
  pinMode(BUZ, OUTPUT);
  bt.begin(9600);
  oled.begin(SSD1306_SWITCHCAPACITOR_VCC, 0x3C);
  Wire.setClock(400000);
  oled.setTextSize(1);
  oled.setTextColor(WHITE);
  oled.clearDisplay();
}

void loop() {
  while (bt.available()) {
    char c = bt.read();
    if (c == '\n') {
      handleLine();
      rxLen = 0;
    } else if (c != '\r') {
      if (rxLen < MAXCH + 4) rx[rxLen++] = c;
    }
  }

  if (digitalRead(BTN) == LOW) {
    bt.println("LISTEN");
    tone(BUZ, 1000, 100);
    delay(700);
  }

  if (mode == TEXT) loopText();
  else loopFace();
}
