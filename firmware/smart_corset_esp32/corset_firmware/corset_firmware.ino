// ============================================================
// ПРОШИВКА КОРСЕТА — ИСПРАВЛЕННАЯ ВЕРСИЯ
// Изменения:
//   1. Задержка снижена с 200ms до 100ms (данные 10 Гц вместо 5)
//   2. Добавлена передача уровня заряда батареи
//   3. Исправлен формат данных: "angle;motorState;battery"
// ============================================================

#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <Wire.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <BLE2902.h>

// --- НАСТРОЙКИ ---
Adafruit_MPU6050 mpu;
#define MOTOR_PIN    18
#define BATTERY_PIN  34  // ADC пин для измерения батареи (подключи делитель напряжения)
                         // Если батарейного АЦП нет — будет симуляция разряда

float baseAngle = 0;
bool baseSet = false;

// Для симуляции заряда (если нет реального АЦП)
int simulatedBattery = 100;
unsigned long lastBatteryUpdate = 0;

// --- UUID (не меняй — должны совпадать с Android) ---
#define SERVICE_UUID              "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define DATA_CHARACTERISTIC_UUID  "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define COMMAND_CHARACTERISTIC_UUID "a2e88a38-36e1-4688-b7f5-ea07361b26a8"

BLECharacteristic *pDataCharacteristic;
bool deviceConnected = false;

// --- ФУНКЦИЯ ИЗМЕРЕНИЯ БАТАРЕИ ---
// Если у тебя реальный делитель напряжения на пине BATTERY_PIN:
//   - Подключи + батареи через делитель (100кОм + 100кОм) к пину 34
//   - Раскомментируй строку analogRead ниже
// Если нет АЦП — используется симуляция (заряд падает каждые 3 минуты)
int getBatteryLevel() {
  // === ВАРИАНТ 1: Реальное измерение АЦП ===
  // int rawAdc = analogRead(BATTERY_PIN); // 0..4095
  // float voltage = (rawAdc / 4095.0) * 3.3 * 2.0; // *2 из-за делителя
  // // Li-Ion: 4.2V = 100%, 3.0V = 0%
  // int level = constrain((int)((voltage - 3.0) / (4.2 - 3.0) * 100), 0, 100);
  // return level;

  // === ВАРИАНТ 2: Симуляция (убери когда добавишь АЦП) ===
  unsigned long now = millis();
  if (now - lastBatteryUpdate > 180000) { // Каждые 3 минуты -1%
    if (simulatedBattery > 0) simulatedBattery--;
    lastBatteryUpdate = now;
  }
  return simulatedBattery;
}

// --- BLUETOOTH CALLBACKS ---
class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    deviceConnected = true;
    Serial.println("Client Connected!");
  }
  void onDisconnect(BLEServer* pServer) {
    deviceConnected = false;
    Serial.println("Client Disconnected! Restarting advertising...");
    delay(300);
    BLEDevice::startAdvertising();
  }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) {
    String value = pCharacteristic->getValue();
    if (value.length() > 0) {
      Serial.print("Command: "); Serial.println(value);

      if (value == "SET") {
        sensors_event_t a, g, temp;
        mpu.getEvent(&a, &g, &temp);
        baseAngle = atan2(a.acceleration.x, a.acceleration.z) * 57.2958;
        baseSet = true;
        Serial.print("Base angle set: "); Serial.println(baseAngle);
      }
      // Команды управления мотором с телефона (опционально)
      else if (value == "MOTOR_ON") {
        digitalWrite(MOTOR_PIN, HIGH);
      }
      else if (value == "MOTOR_OFF") {
        digitalWrite(MOTOR_PIN, LOW);
      }
    }
  }
};

// --- SETUP ---
void setup() {
  Serial.begin(115200);
  Wire.begin(21, 22);

  pinMode(MOTOR_PIN, OUTPUT);
  digitalWrite(MOTOR_PIN, LOW);

  if (!mpu.begin()) {
    Serial.println("MPU6050 NOT FOUND!");
    while (1) delay(10);
  }
  mpu.setAccelerometerRange(MPU6050_RANGE_4_G);
  mpu.setGyroRange(MPU6050_RANGE_500_DEG);
  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);
  Serial.println("MPU6050 OK");

  BLEDevice::init("МРК КВ - 1");
  BLEServer *pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  pDataCharacteristic = pService->createCharacteristic(
    DATA_CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  pDataCharacteristic->addDescriptor(new BLE2902());

  BLECharacteristic *pCommandCharacteristic = pService->createCharacteristic(
    COMMAND_CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_WRITE
  );
  pCommandCharacteristic->setCallbacks(new CommandCallbacks());

  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  BLEDevice::startAdvertising();

  lastBatteryUpdate = millis();
  Serial.println("Ready!");
}

// --- LOOP ---
void loop() {
  sensors_event_t a, g, temp;
  mpu.getEvent(&a, &g, &temp);

  float angle = atan2(a.acceleration.x, a.acceleration.z) * 57.2958;

  if (baseSet) {
    float deviation = abs(angle - baseAngle);
    const float THRESHOLD = 5.0;
    bool isMotorOn = deviation > THRESHOLD;
    digitalWrite(MOTOR_PIN, isMotorOn ? HIGH : LOW);

    if (deviceConnected) {
      int battery = getBatteryLevel();

      // ФОРМАТ: "angle;motorState;battery"
      // Пример: "12.34;1;87"
      String dataString = String(angle, 2) + ";" + String(isMotorOn ? 1 : 0) + ";" + String(battery);

      pDataCharacteristic->setValue(dataString.c_str());
      pDataCharacteristic->notify();

      Serial.println(dataString); // Для отладки
    }
  }

  delay(100); // 100ms вместо 200ms — данные идут вдвое быстрее
}
