#!/usr/bin/env python3
"""
Test script pour simuler la fin d'un trajet et vérifier la sauvegarde
"""
import subprocess
import time
import json
import sys

def run_adb_command(cmd):
    """Exécute une commande adb et retourne le résultat"""
    try:
        result = subprocess.run(
            ["adb", "-s", "RFCY400VHXH"] + cmd.split(),
            capture_output=True,
            text=True,
            check=True
        )
        return result.stdout.strip()
    except subprocess.CalledProcessError as e:
        print(f"Erreur ADB: {e}")
        return None

def stop_location_service():
    """Force l'arrêt du service de géolocalisation pour déclencher finishCurrentTrip()"""
    print("🔴 Arrêt forcé du service LocationTrackingService...")

    # Méthode 1: Arrêter via l'intent
    run_adb_command("shell am stopservice com.application.motium/.service.LocationTrackingService")

    # Méthode 2: Arrêter l'app complètement puis la redémarrer
    run_adb_command("shell am force-stop com.application.motium")
    time.sleep(2)

    print("✅ Service arrêté")

def check_trip_logs():
    """Vérifier les logs pour voir si le trajet a été sauvegardé"""
    print("📋 Vérification des logs de sauvegarde...")

    # Capture les logs récents
    logs = run_adb_command("logcat -d | grep -E '(TripTracker|DatabaseSave|Trip.*saved|TRIP REJECTED)'")

    if logs:
        print("\n--- LOGS DE TRAJET ---")
        for line in logs.split('\n')[-20:]:  # Dernières 20 lignes
            if line.strip():
                print(line)
        print("--- FIN LOGS ---\n")
    else:
        print("❌ Aucun log de trajet trouvé")

    return logs

def simulate_trip_end():
    """Simule la fin d'un trajet en cours"""
    print("🚀 Test de fin de trajet et sauvegarde")
    print("=" * 50)

    # Étape 1: Vérifier qu'un trajet est en cours
    print("1️⃣  Vérification du trajet en cours...")

    # Étape 2: Forcer l'arrêt pour déclencher finishCurrentTrip()
    stop_location_service()

    # Étape 3: Attendre un peu pour que la sauvegarde s'exécute
    print("⏳ Attente de la sauvegarde (5 secondes)...")
    time.sleep(5)

    # Étape 4: Vérifier les logs
    logs = check_trip_logs()

    # Étape 5: Analyser les résultats
    print("\n📊 ANALYSE DES RÉSULTATS:")

    if "Trip saved successfully" in logs:
        print("✅ SUCCÈS: Trajet sauvegardé avec succès !")
    elif "TRIP REJECTED" in logs:
        print("⚠️  REJETÉ: Le trajet n'a pas passé les critères de validation")
    else:
        print("❓ INCONNU: Impossible de déterminer le statut")

    # Chercher les détails spécifiques
    if "distance=" in logs:
        # Extraire les métriques du trajet
        for line in logs.split('\n'):
            if "distance=" in line and "duration=" in line:
                print(f"📏 Métriques: {line.split('distance=')[1].split(',')[0]}")
                break

    print("\n" + "=" * 50)

if __name__ == "__main__":
    simulate_trip_end()