#!/usr/bin/env python3
"""
Script pour simuler un trajet GPS sur l'émulateur Android.
Simule un trajet de Lyon à Aix-les-Bains (comme dans l'interface de l'app).
"""

import subprocess
import time
import sys

def send_location(lat, lng):
    """Envoie une position GPS à l'émulateur."""
    cmd = f'adb -s emulator-5554 emu geo fix {lng} {lat}'
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if result.returncode == 0:
        print(f"✓ Position envoyée: {lat}, {lng}")
    else:
        print(f"✗ Erreur: {result.stderr}")
    return result.returncode == 0

def simulate_route():
    """Simule un trajet de Lyon vers Aix-les-Bains."""

    # Points du trajet Lyon -> Aix-les-Bains (coordonnées approximatives)
    route_points = [
        # Lyon départ
        (45.7640, 4.8357),
        (45.7680, 4.8420),
        (45.7720, 4.8580),
        # Sortie de Lyon vers l'A42
        (45.7850, 4.8800),
        (45.8100, 4.9200),
        (45.8350, 4.9800),
        # Sur l'autoroute A42
        (45.8500, 5.0200),
        (45.8600, 5.0800),
        (45.8700, 5.1400),
        (45.8800, 5.2000),
        # Approche Chambéry
        (45.8900, 5.2600),
        (45.9000, 5.3200),
        (45.9100, 5.3800),
        # Vers Aix-les-Bains
        (45.6900, 5.9100),
        (45.6950, 5.9150),
        # Aix-les-Bains arrivée
        (45.6885, 5.9158)  # Coordonnées d'Aix-les-Bains
    ]

    print("🚗 Début de la simulation du trajet Lyon -> Aix-les-Bains")
    print("📱 Assurez-vous que l'application Motium est ouverte et le suivi activé")
    print("⏳ Appuyez sur Ctrl+C pour arrêter la simulation\n")

    try:
        for i, (lat, lng) in enumerate(route_points):
            print(f"📍 Point {i+1}/{len(route_points)}: {lat}, {lng}")

            if send_location(lat, lng):
                # Attendre entre 3 et 10 secondes selon la position
                if i < 3:  # Début du trajet, mouvements plus lents
                    wait_time = 8
                elif i < len(route_points) - 3:  # Autoroute, plus rapide
                    wait_time = 3
                else:  # Arrivée, plus lent
                    wait_time = 6

                print(f"⏸️  Attente {wait_time} secondes...\n")
                time.sleep(wait_time)
            else:
                print("❌ Erreur lors de l'envoi de la position, arrêt de la simulation")
                break

    except KeyboardInterrupt:
        print("\n🛑 Simulation interrompue par l'utilisateur")

    print("\n✅ Simulation terminée !")
    print("📊 Vérifiez les logs dans l'application (appui long sur 'Paramètres')")

if __name__ == "__main__":
    print("=== Simulateur GPS Motium ===\n")

    # Vérifier que l'émulateur est connecté
    result = subprocess.run("adb -s emulator-5554 get-state", shell=True, capture_output=True, text=True)
    if result.returncode != 0:
        print("❌ Erreur: L'émulateur emulator-5554 n'est pas connecté")
        print("Vérifiez que l'émulateur Android est démarré")
        sys.exit(1)

    simulate_route()