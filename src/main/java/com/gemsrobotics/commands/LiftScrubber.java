package com.gemsrobotics.commands;

import com.gemsrobotics.subsystems.lift.Lift;
import edu.wpi.first.wpilibj.GenericHID.Hand;
import edu.wpi.first.wpilibj.XboxController;
import edu.wpi.first.wpilibj.command.Command;

public class LiftScrubber extends Command {
	private final Lift m_lift;
	private final XboxController m_controller;

	private static final double
		MAX_DISTANCE_PER_SECOND = 0.5,
		MAX_DISTANCE_PER_TICK = MAX_DISTANCE_PER_SECOND / 50.0;

	public LiftScrubber(final Lift lift, final XboxController controller) {
		m_lift = lift;
		m_controller = controller;
	}

	@Override
	public void execute() {
		final double rate = m_controller.getTriggerAxis(Hand.kRight) -
			m_controller.getTriggerAxis(Hand.kLeft);

		m_lift.adjustPosition(-rate * MAX_DISTANCE_PER_TICK);
	}

	@Override
	public boolean isFinished() {
		return false;
	}
}
