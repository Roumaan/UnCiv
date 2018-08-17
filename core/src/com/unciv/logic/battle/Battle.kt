package com.unciv.logic.battle

import com.badlogic.gdx.graphics.Color
import com.unciv.UnCivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.city.CityInfo
import com.unciv.logic.map.TileInfo
import com.unciv.models.gamebasics.unit.UnitType
import java.util.*
import kotlin.math.max

/**
 * Damage calculations according to civ v wiki and https://steamcommunity.com/sharedfiles/filedetails/?id=170194443
 */
class Battle(val gameInfo:GameInfo=UnCivGame.Current.gameInfo) {
    fun attack(attacker: ICombatant, defender: ICombatant) {
        val attackedTile = defender.getTile()

        var damageToDefender = BattleDamage().calculateDamageToDefender(attacker,defender)
        var damageToAttacker = BattleDamage().calculateDamageToAttacker(attacker,defender)

        if(defender.getUnitType() == UnitType.Civilian && attacker.isMelee()){
            captureCivilianUnit(attacker,defender)
        }
        else if (attacker.isRanged()) {
            defender.takeDamage(damageToDefender) // straight up
        } else {
            //melee attack is complicated, because either side may defeat the other midway
            //so...for each round, we randomize who gets the attack in. Seems to be a good way to work for now.

            while (damageToDefender + damageToAttacker > 0) {
                if (Random().nextInt(damageToDefender + damageToAttacker) < damageToDefender) {
                    damageToDefender--
                    defender.takeDamage(1)
                    if (defender.isDefeated()) break
                } else {
                    damageToAttacker--
                    attacker.takeDamage(1)
                    if (attacker.isDefeated()) break
                }
            }
        }

        postBattleAction(attacker,defender,attackedTile)
    }

    private fun postBattleAction(attacker: ICombatant, defender: ICombatant, attackedTile:TileInfo){

        if(attacker.getCivilization()!=defender.getCivilization()) { // If what happened was that a civilian unit was captures, that's dealt with in the CaptureCilvilianUnit function
            val whatHappenedString =
                    if (attacker.isDefeated()) " {was destroyed while attacking}"
                    else " has " + (if (defender.isDefeated()) "destroyed" else "attacked")
            val defenderString =
                    if (defender.getUnitType() == UnitType.City) " [" + defender.getName()+"]"
                    else " our [" + defender.getName()+"]"
            val notificationString = "An enemy [" + attacker.getName()+"]" + whatHappenedString + defenderString
            defender.getCivilization().addNotification(notificationString, attackedTile.position, Color.RED)
        }


        if(defender.isDefeated()
                && defender.getUnitType() == UnitType.City
                && attacker.isMelee()){
            conquerCity((defender as CityCombatant).city, attacker)
        }

        // we're a melee unit and we destroyed\captured an enemy unit
        else if (attacker.isMelee()
                && (defender.isDefeated() || defender.getCivilization()==attacker.getCivilization() )
                // This is so that if we attack e.g. a barbarian in enemy territory that we can't enter, we won't enter it
                && (attacker as MapUnitCombatant).unit.canMoveTo(attackedTile)) {
            // we destroyed an enemy military unit and there was a civilian unit in the same tile as well
            if(attackedTile.civilianUnit!=null && attackedTile.civilianUnit!!.civInfo != attacker.getCivilization())
                captureCivilianUnit(attacker,MapUnitCombatant(attackedTile.civilianUnit!!))
            attacker.unit.moveToTile(attackedTile)
        }

        if(attacker is MapUnitCombatant) {
            val unit = attacker.unit
            if (unit.hasUnique("Can move after attacking")){
                if(!attacker.getUnitType().isMelee() || !defender.isDefeated()) // if it was a melee attack and we won, then the unit ALREADY got movement points deducted, for the movement to the enemie's tile!
                    unit.currentMovement = max(0f, unit.currentMovement - 1)
            }
            else unit.currentMovement = 0f
            unit.attacksThisTurn+=1
            if(unit.isFortified()) attacker.unit.action=null // but not, for instance, if it's Set Up - then it should definitely keep the action!
        }

        // XP!
        if(attacker.isMelee()){
            if(defender.getUnitType()!=UnitType.Civilian) // unit was not captured but actually attacked
            {
                if (attacker is MapUnitCombatant) attacker.unit.promotions.XP += 5
                if (defender is MapUnitCombatant) defender.unit.promotions.XP += 4
            }
        }
        else{ // ranged attack
            if(attacker is MapUnitCombatant) attacker.unit.promotions.XP += 2
            if(defender is MapUnitCombatant) defender.unit.promotions.XP += 2
        }

        if(attacker is MapUnitCombatant && attacker.unit.action!=null && attacker.unit.action!!.startsWith("moveTo"))
            attacker.unit.action=null
    }

    private fun conquerCity(city: CityInfo, attacker: ICombatant) {
        val enemyCiv = city.civInfo
        attacker.getCivilization().addNotification("We have conquered the city of [${city.name}]!",city.location, Color.RED)
        val currentPopulation = city.population.population
        if(currentPopulation>1) city.population.population -= 1 + currentPopulation/4 // so from 2-4 population, remove 1, from 5-8, remove 2, etc.
        city.moveToCiv(attacker.getCivilization())
        city.health = city.getMaxHealth() / 2 // I think that cities recover to half health when conquered?
        city.getCenterTile().apply {
            militaryUnit = null
            if(civilianUnit!=null) captureCivilianUnit(attacker,MapUnitCombatant(civilianUnit!!))
        }

        if(city.cityConstructions.isBuilt("Palace")){
            city.cityConstructions.builtBuildings.remove("Palace")
            if(enemyCiv.isDefeated()) {
                gameInfo.getPlayerCivilization()
                        .addNotification("The civilization of [${enemyCiv.civName}] has been destroyed!", null, Color.RED)
                enemyCiv.getCivUnits().forEach { it.destroy() }
            }
            else if(enemyCiv.cities.isNotEmpty()){
                enemyCiv.cities.first().cityConstructions.builtBuildings.add("Palace") // relocate palace
            }
        }

        (attacker as MapUnitCombatant).unit.moveToTile(city.getCenterTile())
    }

    fun getMapCombatantOfTile(tile:TileInfo): ICombatant? {
        if(tile.isCityCenter()) return CityCombatant(tile.getCity()!!)
        if(tile.militaryUnit!=null) return MapUnitCombatant(tile.militaryUnit!!)
        if(tile.civilianUnit!=null) return MapUnitCombatant(tile.civilianUnit!!)
        return null
    }

    fun captureCivilianUnit(attacker: ICombatant, defender: ICombatant){
        if(attacker.getCivilization().isBarbarianCivilization()){
            defender.takeDamage(100)
            return
        } // barbarians don't capture civilians!
        val capturedUnit = (defender as MapUnitCombatant).unit
        capturedUnit.civInfo.addNotification("An enemy ["+attacker.getName()+"] has captured our ["+defender.getName()+"]",
                defender.getTile().position, Color.RED)

        capturedUnit.civInfo.units.remove(capturedUnit)
        capturedUnit.assignOwner(attacker.getCivilization())
    }
}