package com.planet_ink.coffee_mud.Abilities.Traps;

import com.planet_ink.coffee_mud.interfaces.*;
import com.planet_ink.coffee_mud.common.*;
import java.util.*;

public class Trap_OpenSpell extends Trap_Open
{
	public Trap_OpenSpell()
	{
		super();
		myID=this.getClass().getName().substring(this.getClass().getName().lastIndexOf('.')+1);
		canTargetCode=0;
		canAffectCode=Ability.CAN_ITEMS|Ability.CAN_EXITS;
		baseEnvStats().setAbility(Trap.TRAP_SPELL);
		setMiscText("Spell_Sleep");
		recoverEnvStats();
	}

	public Environmental newInstance()
	{
		return new Trap_OpenSpell();
	}
}
