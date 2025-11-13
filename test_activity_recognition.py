#!/usr/bin/env python3
"""
Script pour tester l'Activity Recognition avec simulation de trajet GPS.
Ce script va d'abord simuler un état "immobile", puis un déplacement en voiture,
puis revenir à l'état "à pied" pour tester la détection intelligente.
"""

import subprocess
import time
import sys

def send_location_with_speed(lat, lng, speed=0):
    """Envoie une position GPS à l'émulateur avec une vitesse simulée."""
    # Envoyer la position
    cmd_location = f'adb -s emulator-5554 emu geo fix {lng} {lat}'
    result_location = subprocess.run(cmd_location, shell=True, capture_output=True, text=True)

    if result_location.returncode == 0:
        speed_kmh = speed * 3.6  # Conversion m/s en km/h
        print(f"✓ Position: {lat}, {lng} (vitesse: {speed_kmh:.1f} km/h)")
        return True
    else:
        print(f"✗ Erreur position: {result_location.stderr}")
        return False

def simulate_intelligent_trip():
    """
    Simule un trajet intelligent pour tester Activity Recognition:
    1. Phase immobile (STILL)
    2. Phase véhicule (IN_VEHICLE) avec mouvement rapide
    3. Phase à pied (ON_FOOT) avec mouvement lent
    """

    print("🧠 Test du système de détection intelligente Motium")
    print("📱 Assurez-vous que l'application Motium est ouverte et le suivi activé")
    print("⏳ Le test va simuler différents types de mouvements\n")

    try:
        # Phase 1: IMMOBILE (5 minutes pour bien s'installer)
        print("🛑 PHASE 1: Simulation état IMMOBILE")
        print("   L'Activity Recognition devrait détecter STILL")
        print("   Le GPS ne devrait PAS être activé")

        base_lat, base_lng = 45.7640, 4.8357  # Lyon centre

        for i in range(10):  # 10 x 3 sec = 30 secondes immobile
            send_location_with_speed(base_lat, base_lng, 0)  # Vitesse 0
            if i < 9:
                print(f"   Immobile {i+1}/10... (3s)")
                time.sleep(3)

        print("\n🚗 PHASE 2: Simulation DÉPLACEMENT EN VÉHICULE")
        print("   L'Activity Recognition devrait détecter IN_VEHICLE")
        print("   Le GPS devrait S'ACTIVER et commencer l'enregistrement")

        # Trajet Lyon -> Aix-les-Bains avec vitesse élevée (50+ km/h)
        vehicle_points = [
            # Sortie de Lyon - vitesse élevée
            (45.7680, 4.8420, 15),  # ~54 km/h
            (45.7720, 4.8580, 16),  # ~58 km/h
            (45.7850, 4.8800, 18),  # ~65 km/h
            # Autoroute - vitesse très élevée
            (45.8100, 4.9200, 25),  # ~90 km/h
            (45.8350, 4.9800, 28),  # ~100 km/h
            (45.8500, 5.0200, 30),  # ~108 km/h
            (45.8600, 5.0800, 28),  # ~100 km/h
            (45.8700, 5.1400, 25),  # ~90 km/h
            # Approche destination
            (45.8800, 5.2000, 20),  # ~72 km/h
            (45.8900, 5.2600, 15),  # ~54 km/h
            (45.6900, 5.9100, 12),  # ~43 km/h
        ]

        for i, (lat, lng, speed) in enumerate(vehicle_points):
            send_location_with_speed(lat, lng, speed)
            if i < len(vehicle_points) - 1:
                print(f"   Déplacement véhicule {i+1}/{len(vehicle_points)} (5s)")
                time.sleep(5)  # 5 secondes entre points véhicule

        print("\n🚶 PHASE 3: Simulation MARCHE À PIED")
        print("   L'Activity Recognition devrait détecter WALKING/ON_FOOT")
        print("   Le GPS devrait S'ARRÊTER et sauvegarder le trajet")

        # Arrivée avec mouvement très lent (marche)
        final_lat, final_lng = 45.6885, 5.9158  # Aix-les-Bains centre
        walking_points = [
            (45.6890, 5.9155, 1.2),  # ~4.3 km/h (marche)
            (45.6888, 5.9157, 1.0),  # ~3.6 km/h (marche lente)
            (45.6885, 5.9158, 0.5),  # ~1.8 km/h (marche très lente)
            (45.6885, 5.9158, 0),    # Arrêt complet
        ]

        for i, (lat, lng, speed) in enumerate(walking_points):
            send_location_with_speed(lat, lng, speed)
            if i < len(walking_points) - 1:
                print(f"   Marche {i+1}/{len(walking_points)} (8s)")
                time.sleep(8)  # 8 secondes entre points marche

        print("\n🛑 PHASE 4: Confirmation ARRÊT COMPLET")
        print("   Confirmation de l'arrêt définitif")

        for i in range(5):  # 5 points immobiles pour confirmer
            send_location_with_speed(final_lat, final_lng, 0)
            if i < 4:
                print(f"   Arrêt confirmé {i+1}/5 (5s)")
                time.sleep(5)

    except KeyboardInterrupt:
        print("\n🛑 Test interrompu par l'utilisateur")

    print("\n✅ Test du système intelligent terminé !")
    print("📊 Vérifications à faire dans Motium :")
    print("   1. Vérifiez les logs (appui long sur 'Paramètres')")
    print("   2. Le trajet devrait être visible dans l'accueil")
    print("   3. Distance: ~115 km, Durée: ~3-4 minutes de simulation")
    print("   4. Notifications devaient changer : Immobile → Véhicule → À pied")

if __name__ == "__main__":
    print("=== Test Motium - Système Intelligent ===\n")

    # Vérifier que l'émulateur est connecté
    result = subprocess.run("adb -s emulator-5554 get-state", shell=True, capture_output=True, text=True)
    if result.returncode != 0:
        print("❌ Erreur: L'émulateur emulator-5554 n'est pas connecté")
        print("Vérifiez que l'émulateur Android est démarré")
        sys.exit(1)

    simulate_intelligent_trip()