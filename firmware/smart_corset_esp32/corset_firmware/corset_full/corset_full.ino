#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <Wire.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <BLE2902.h>

#define MOTOR_PIN    14
#define BATTERY_PIN  34
#define DEFAULT_THRESHOLD 5.0f
#define BATTERY_SAMPLES           24
#define BATTERY_UPDATE_MS         3000UL
#define BATTERY_SETTLE_MS         1500UL
#define BATTERY_FILTER_ALPHA      0.15f

#define SERVICE_UUID              "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define DATA_CHARACTERISTIC_UUID  "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define CMD_CHARACTERISTIC_UUID   "a2e88a38-36e1-4688-b7f5-ea07361b26a8"

Adafruit_MPU6050 mpu;
BLECharacteristic *pDataCharacteristic;

float baseAngle = 0;
bool baseSet = false;
bool deviceConnected = false;
bool lastMotorOn = false;
unsigned long lastBatterySampleMs = 0;
unsigned long lastMotorOffMs = 0;
float filteredBatteryVoltage = -1.0f;
int cachedBatteryLevel = 0;
float alertThreshold = DEFAULT_THRESHOLD;

float readBatteryVoltage() {
  uint32_t sumMv = 0;

  for (int i = 0; i < BATTERY_SAMPLES; i++) {
    sumMv += analogReadMilliVolts(BATTERY_PIN);
    delay(2);
  }

  float pinVoltage = (sumMv / (float)BATTERY_SAMPLES) / 1000.0f;
  return pinVoltage * 2.0f;
}

int voltageToPercent(float voltage) {
  return constrain((int)roundf((voltage - 3.0f) / 1.2f * 100.0f), 0, 100);
}

int getBatteryLevel(bool motorOn) {
  unsigned long now = millis();

  if (lastMotorOn && !motorOn) {
    lastMotorOffMs = now;
  }

  bool readyForSample = !motorOn && (now - lastMotorOffMs >= BATTERY_SETTLE_MS);
  bool needsSample = filteredBatteryVoltage < 0.0f || (now - lastBatterySampleMs >= BATTERY_UPDATE_MS);

  if (readyForSample && needsSample) {
    float voltage = readBatteryVoltage();
    if (filteredBatteryVoltage < 0.0f) {
      filteredBatteryVoltage = voltage;
    } else {
      filteredBatteryVoltage =
        filteredBatteryVoltage * (1.0f - BATTERY_FILTER_ALPHA) +
        voltage * BATTERY_FILTER_ALPHA;
    }

    cachedBatteryLevel = voltageToPercent(filteredBatteryVoltage);
    lastBatterySampleMs = now;
  }

  return cachedBatteryLevel;
}

float getAngle() {
  sensors_event_t a, g, temp;
  mpu.getEvent(&a, &g, &temp);
  return atan2(a.acceleration.x, a.acceleration.z) * 57.2958f;
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    deviceConnected = true;
  }
  void onDisconnect(BLEServer*) override {
    deviceConnected = false;
    delay(300);
    BLEDevice::startAdvertising();
  }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pChar) override {
    String cmd = pChar->getValue();
    if (cmd == "SET") {
      baseAngle = getAngle();
      baseSet = true;
    } else if (cmd == "MOTOR_ON") {
      digitalWrite(MOTOR_PIN, HIGH);
    } else if (cmd == "MOTOR_OFF") {
      digitalWrite(MOTOR_PIN, LOW);
    } else if (cmd.startsWith("THR:")) {
      float newThreshold = cmd.substring(4).toFloat();
      if (newThreshold >= 3.0f && newThreshold <= 12.0f) {
        alertThreshold = newThreshold;
      }
    }
  }
};

void setup() {
  Serial.begin(115200);
  Wire.begin(21, 22);

  pinMode(MOTOR_PIN, OUTPUT);
  digitalWrite(MOTOR_PIN, LOW);

  analogReadResolution(12);
  analogSetPinAttenuation(BATTERY_PIN, ADC_11db);

  if (!mpu.begin()) Serial.println("MPU6050 not found!");
  mpu.setAccelerometerRange(MPU6050_RANGE_4_G);
  mpu.setGyroRange(MPU6050_RANGE_500_DEG);
  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);

  BLEDevice::init("CorsetV");
  BLEServer *pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  pDataCharacteristic = pService->createCharacteristic(
    DATA_CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  pDataCharacteristic->addDescriptor(new BLE2902());

  BLECharacteristic *pCmd = pService->createCharacteristic(
    CMD_CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_WRITE
  );
  pCmd->setCallbacks(new CommandCallbacks());

  pService->start();
  BLEAdvertising *pAdv = BLEDevice::getAdvertising();
  pAdv->addServiceUUID(SERVICE_UUID);
  pAdv->setScanResponse(true);
  BLEDevice::startAdvertising();

  filteredBatteryVoltage = readBatteryVoltage();
  cachedBatteryLevel = voltageToPercent(filteredBatteryVoltage);
  lastMotorOffMs = millis();
  lastBatterySampleMs = 0;

  Serial.println("CorsetV ready!");
}

void loop() {
  if (!baseSet) { delay(100); return; }

  float angle = getAngle();
  bool motorOn = abs(angle - baseAngle) > alertThreshold;
  digitalWrite(MOTOR_PIN, motorOn ? HIGH : LOW);
  int batteryLevel = getBatteryLevel(motorOn);
  lastMotorOn = motorOn;

  if (deviceConnected) {
    String data = String(angle, 2) + ";" + motorOn + ";" + batteryLevel;
    pDataCharacteristic->setValue(data.c_str());
    pDataCharacteristic->notify();
  }

  delay(100);
}
