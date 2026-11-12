| Device       | Pin    | ESP32 GPIO |
| ------------ | ------ | ---------- |
| OLED SDA     | SDA    | GPIO5      |
| OLED SCL     | SCL    | GPIO6      |
| INA219 SDA   | SDA    | GPIO5      |
| INA219 SCL   | SCL    | GPIO6      |
| TB6612 PWMA  | PWM    | GPIO25     |
| TB6612 AIN1  | DIR    | GPIO26     |
| TB6612 AIN2  | DIR    | GPIO27     |
| TB6612 PWMB  | PWM    | GPIO14     |
| TB6612 BIN1  | DIR    | GPIO12     |
| TB6612 BIN2  | DIR    | GPIO13     |
| TB6612 STBY  | Enable | GPIO33     |
| Touch Sensor | OUT    | GPIO4      |
| Push Button  | Input  | GPIO15     |


1. ESP32 DevKit V1 (Main Controller)

This ESP32 controls:

OLED RoboEyes
TB6612FNG Motor Driver
INA219 Battery Monitor
TTP223 Touch Sensor
Push Button
Wi-Fi Access Point
Telemetry
Communication with ESP32-CAM
2. OLED Display (SSD1306 I2C)
OLED Pin	ESP32
VCC	3.3V
GND	GND
SDA	GPIO5
SCL	GPIO6
3. INA219 Current/Voltage Sensor

Battery must pass through INA219 before powering the motor driver.

INA219	ESP32
VCC	3.3V
GND	GND
SDA	GPIO5
SCL	GPIO6

Battery side:

Battery +
      │
      │
   VIN+ (INA219)

VIN- (INA219)
      │
      │
      VM (TB6612FNG)

Battery negative goes directly to GND.

4. TB6612FNG Motor Driver
Logic Connections
TB6612FNG	ESP32
PWMA	GPIO25
AIN1	GPIO26
AIN2	GPIO27
PWMB	GPIO14
BIN1	GPIO12
BIN2	GPIO13
STBY	GPIO33
VCC	3.3V
GND	GND
Motor Connections

Motor A

A01 ---- Left Motor +
A02 ---- Left Motor -

Motor B

B01 ---- Right Motor +
B02 ---- Right Motor -
Power
Battery +
      │
      VM

Battery -
      │
      GND
5. TTP223 Touch Sensor
TTP223	ESP32
VCC	3.3V
GND	GND
OUT	GPIO4
6. Push Button
GPIO15 -------- Button -------- GND

Internal pull-up enabled in software.

7. ESP32-CAM

Power only.

ESP32-CAM	Connection
5V	5V Regulator
GND	Common Ground

No GPIO wiring is needed because it communicates with the main ESP32 over Wi-Fi.

8. Power Supply
        2S Li-ion Battery
          (7.4V Nominal)

              │
              │
          INA219 VIN+

INA219 VIN-
              │
              │
          TB6612 VM

Battery -
              │
              ├──────── ESP32 GND
              ├──────── TB6612 GND
              ├──────── INA219 GND
              ├──────── OLED GND
              └──────── ESP32-CAM GND
9. ESP32 Power

Use a buck converter to power the ESP32 from the battery.

Battery (7.4V)

        │

 Buck Converter
7.4V → 5V

        │

ESP32 VIN

Do not power the ESP32 directly from the 7.4V battery.

10. I2C Bus

Both OLED and INA219 share the same I2C bus.

GPIO5 (SDA)
     │
     ├── OLED SDA
     └── INA219 SDA

GPIO6 (SCL)
     │
     ├── OLED SCL
     └── INA219 SCL
11. Wi-Fi Communication
                Laptop
                   │
                   │
          Wi-Fi (COOKIE_BOT)
                   │
        ┌──────────┴──────────┐
        │                     │
   ESP32 Robot          ESP32-CAM