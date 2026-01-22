#!/usr/bin/env python3
"""
Simulation de trajet Chambéry -> Lyon avec sortie d'autoroute
Durée: 20 minutes (simulation accélérée)
Inclut: Autoroute A43, sortie Bourgoin-Jallieu, départementale, retour autoroute
"""

import subprocess
import time
import math

# Points clés du trajet (lat, lon, description)
WAYPOINTS = [
    # Départ Chambéry
    (45.5646, 5.9178, "Départ Chambéry"),
    (45.5680, 5.8900, "A43 - Sortie Chambéry"),
    (45.5720, 5.8500, "A43 - Direction Lyon"),
    (45.5780, 5.8000, "A43 - Km 10"),
    (45.5820, 5.7500, "A43 - Km 20"),
    (45.5850, 5.7000, "A43 - Km 30"),
    (45.5870, 5.6500, "A43 - Km 40"),
    (45.5880, 5.6000, "A43 - Approche sortie"),

    # Sortie autoroute vers départementale (Bourgoin-Jallieu)
    (45.5867, 5.5800, "SORTIE - Bretelle de sortie"),
    (45.5850, 5.5700, "SORTIE - Rond-point sortie"),
    (45.5830, 5.5600, "D1006 - Entrée départementale"),
    (45.5800, 5.5400, "D1006 - Route départementale"),
    (45.5780, 5.5200, "D1006 - Traversée village"),
    (45.5760, 5.5000, "D1006 - Ligne droite"),
    (45.5750, 5.4800, "D1006 - Virage"),
    (45.5760, 5.4600, "D1006 - Approche autoroute"),

    # Retour sur autoroute
    (45.5780, 5.4400, "ENTRÉE - Bretelle d'accès"),
    (45.5800, 5.4200, "ENTRÉE - Accélération"),
    (45.5850, 5.4000, "A43 - Retour autoroute"),

    # Autoroute vers Lyon
    (45.5900, 5.3500, "A43 - Km 60"),
    (45.6000, 5.3000, "A43 - Km 70"),
    (45.6200, 5.2500, "A432 - Échangeur"),
    (45.6500, 5.2000, "A432 - Direction Lyon"),
    (45.6800, 5.1500, "A432 - Km 80"),
    (45.7100, 5.1000, "Périphérique Lyon Est"),
    (45.7300, 5.0500, "Approche Lyon"),
    (45.7500, 5.0000, "Lyon - Périphérique"),
    (45.7600, 4.9000, "Lyon - Centre"),
    (45.7640, 4.8357, "Arrivée Lyon"),
]

def interpolate_points(waypoints, total_points):
    """Interpole les waypoints pour avoir un nombre total de points"""
    result = []
    total_segments = len(waypoints) - 1
    points_per_segment = total_points // total_segments

    for i in range(total_segments):
        start = waypoints[i]
        end = waypoints[i + 1]

        for j in range(points_per_segment):
            t = j / points_per_segment
            lat = start[0] + t * (end[0] - start[0])
            lon = start[1] + t * (end[1] - start[1])
            # Ajouter un peu de bruit pour simuler un GPS réel
            lat += (hash(f"{i}{j}lat") % 100 - 50) * 0.00001
            lon += (hash(f"{i}{j}lon") % 100 - 50) * 0.00001
            desc = start[2] if j < points_per_segment // 2 else end[2]
            result.append((lat, lon, desc))

    # Ajouter le dernier point
    result.append(waypoints[-1])
    return result

def set_gps_location(lat, lon, altitude=300):
    """Envoie une position GPS à l'émulateur via ADB"""
    # Utiliser geo fix: longitude latitude [altitude]
    cmd = f'adb emu geo fix {lon} {lat} {altitude}'
    try:
        subprocess.run(cmd, shell=True, capture_output=True, timeout=5)
        return True
    except Exception as e:
        print(f"Erreur GPS: {e}")
        return False

def send_activity_recognition(activity_type="IN_VEHICLE", confidence=100):
    """Envoie un broadcast pour simuler ActivityRecognition"""
    # Broadcast personnalisé pour le TripSimulator de l'app
    cmd = f'adb shell am broadcast -a com.application.motium.MOCK_ACTIVITY -e activity_type {activity_type} -e confidence {confidence}'
    try:
        subprocess.run(cmd, shell=True, capture_output=True, timeout=5)
        return True
    except Exception as e:
        print(f"Erreur Activity: {e}")
        return False

def main():
    print("=" * 60)
    print("SIMULATION TRAJET: Chambéry -> Lyon")
    print("Durée: 20 minutes (accéléré)")
    print("Inclut: Sortie autoroute + départementale")
    print("=" * 60)

    # Durée totale en secondes (20 minutes = 1200 secondes)
    # Pour la simulation, on utilise 10 secondes entre chaque point
    # Donc 120 points pour 20 minutes de trajet simulé
    TOTAL_DURATION_REAL = 20 * 60  # 20 minutes
    TOTAL_POINTS = 120
    INTERVAL_REAL = TOTAL_DURATION_REAL / TOTAL_POINTS  # ~10 secondes

    # Pour la démo, on accélère: 1 seconde réelle = 10 secondes simulées
    SIMULATION_SPEED = 10
    INTERVAL_SIMULATED = INTERVAL_REAL / SIMULATION_SPEED  # ~1 seconde

    # Générer les points interpolés
    points = interpolate_points(WAYPOINTS, TOTAL_POINTS)

    print(f"\nNombre de points GPS: {len(points)}")
    print(f"Intervalle entre points: {INTERVAL_SIMULATED:.1f}s (x{SIMULATION_SPEED} accéléré)")
    print(f"Durée réelle de simulation: {len(points) * INTERVAL_SIMULATED / 60:.1f} minutes")
    print("\n" + "-" * 60)

    # Démarrer la simulation
    start_time = time.time()

    # Envoyer le signal de début de trajet (IN_VEHICLE)
    print("\n[ACTIVITY] Démarrage détection IN_VEHICLE...")
    send_activity_recognition("IN_VEHICLE", 95)

    for i, (lat, lon, desc) in enumerate(points):
        elapsed = time.time() - start_time
        simulated_time = i * INTERVAL_REAL

        # Calculer la vitesse approximative
        if i > 0:
            prev_lat, prev_lon, _ = points[i-1]
            dist = math.sqrt((lat - prev_lat)**2 + (lon - prev_lon)**2) * 111  # km approx
            speed = dist / (INTERVAL_REAL / 3600) if INTERVAL_REAL > 0 else 0
        else:
            speed = 0

        # Afficher la progression
        progress = (i + 1) / len(points) * 100
        sim_minutes = simulated_time // 60
        sim_seconds = simulated_time % 60

        print(f"\r[{progress:5.1f}%] T+{sim_minutes:02.0f}:{sim_seconds:02.0f} | "
              f"GPS: {lat:.4f}, {lon:.4f} | ~{speed:.0f} km/h | {desc[:30]:<30}", end="", flush=True)

        # Envoyer la position GPS
        set_gps_location(lat, lon)

        # Mettre à jour le signal d'activité périodiquement
        if i % 20 == 0:
            send_activity_recognition("IN_VEHICLE", 90 + (i % 10))

        # Attendre avant le prochain point
        time.sleep(INTERVAL_SIMULATED)

    # Fin du trajet - envoyer STILL pour arrêter
    print("\n\n[ACTIVITY] Fin du trajet - Détection STILL...")
    send_activity_recognition("STILL", 95)
    time.sleep(2)
    send_activity_recognition("STILL", 100)

    total_time = time.time() - start_time
    print("\n" + "=" * 60)
    print(f"SIMULATION TERMINÉE")
    print(f"Durée réelle: {total_time/60:.1f} minutes")
    print(f"Durée simulée: 20 minutes")
    print(f"Distance: ~100 km (Chambéry -> Lyon)")
    print("=" * 60)

if __name__ == "__main__":
    main()
