package com.gemsrobotics.subsystems.drive;

import com.gemsrobotics.Constants;
import com.gemsrobotics.OperatorInterface;
import com.gemsrobotics.commands.OpenLoopDriveCommand;
import com.gemsrobotics.commands.ShiftScheduler;
import com.gemsrobotics.util.DualTransmission;
import com.gemsrobotics.util.MyAHRS;
import com.gemsrobotics.util.PIDF;
import com.gemsrobotics.util.camera.Limelight;
import com.revrobotics.CANSparkMax;
import com.revrobotics.CANSparkMaxLowLevel.MotorType;
import com.revrobotics.ControlType;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.wpilibj.Sendable;
import edu.wpi.first.wpilibj.Solenoid;
import edu.wpi.first.wpilibj.command.Subsystem;
import edu.wpi.first.wpilibj.smartdashboard.SendableBuilder;
import edu.wpi.first.wpilibj.smartdashboard.SendableChooser;
import jaci.pathfinder.PathfinderFRC;
import jaci.pathfinder.Trajectory;
import org.usfirst.frc.team3310.utility.control.RobotStateEstimator;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.Math.*;
import static java.lang.Math.min;

@SuppressWarnings({"unused", "WeakerAccess"})
public final class DifferentialDrive extends Subsystem implements Sendable {
	public static final double dt = 0.02;

	private static final double METER_TO_INCHES = 0.0254;
	private static final double DEGREES_THRESHOLD = 3.5;

	private final Specifications m_specifications;
	private final DualTransmission m_transmission;
	private final List<CANSparkMax> m_motors;
	private final MyAHRS m_ahrs;
	private final ShiftScheduler m_shiftScheduler;

	private OpenLoopDriveCommand m_driveCommand;
	private double m_accumulator;
	private String m_name;

	private static final String[] MOTOR_CONTROLLER_NAMES = {
		"Left Front", "Left Back", "Right Front", "Right Back"
	};

	public DifferentialDrive(
			final DrivePorts drivePorts,
			final Specifications specifications,
			final Solenoid shifter,
			final MyAHRS ahrs,
			final boolean useVelocityControl
	) {
		final Integer[] ports = drivePorts.get();
		m_ahrs = ahrs;
		m_specifications = specifications;
		m_transmission = new DualTransmission(shifter);
		m_shiftScheduler = new ShiftScheduler(this);

		if (ports.length != 4) {
			throw new RuntimeException("Wrong number of ports!");
		}

		m_motors = Arrays.stream(ports)
				.map(port -> new CANSparkMax(port, MotorType.kBrushless))
				.collect(Collectors.toList());

		m_motors.get(1).follow(m_motors.get(0));
		m_motors.get(3).follow(m_motors.get(2));
		m_motors.forEach(m -> {
			m.setIdleMode(CANSparkMax.IdleMode.kBrake);
			m.setInverted(false);

			m.getEncoder().setVelocityConversionFactor(m_specifications.getEncoderFactor());
			m.getEncoder().setPositionConversionFactor(m_specifications.getEncoderFactor());

			final var pidController = m.getPIDController();
			m_specifications.pidDrive.configure(pidController, 0);
			pidController.setOutputRange(-1.0, +1.0);
		});

		m_accumulator = 0.0;
	}

	public void configureDriveCommand(
			final Limelight limelight,
			final OperatorInterface controls,
			final SendableChooser<Boolean> toggler
	) {
		m_driveCommand = new OpenLoopDriveCommand(this, limelight, controls, toggler);
	}

	public void setVelocitySetpoints(final double setpointLeft, final double setpointRight) {
		final double maxDesiredVelocity = max(abs(setpointLeft), abs(setpointRight));
		final double maxSetpointVelocity = m_transmission.get().topSpeed;
		final double scale = maxDesiredVelocity > maxSetpointVelocity ? maxSetpointVelocity / maxDesiredVelocity : 1.0;

		getMotor(Side.LEFT).getPIDController().setReference(setpointLeft * scale, ControlType.kVelocity, 0);
		getMotor(Side.RIGHT).getPIDController().setReference(setpointRight * scale, ControlType.kVelocity, 0);
	}

	public void drive(final double leftPower, final double rightPower) {
		getMotor(Side.LEFT).set(leftPower);
		getMotor(Side.RIGHT).set(-rightPower);
	}

	private static final double LIMIT = 1.0;

	private static double limit(final double v) {
		return Math.abs(v) < LIMIT ? v : LIMIT * Math.signum(v);
	}

	private static double constrain(
			final double bot,
			final double val,
			final double top
	) {
		return max(bot, min(val, top));
	}

	public void drive(final DrivePower velocity) {
		curvatureDrive(velocity.linear(), velocity.angular(), false);
	}

	public void curvatureDrive(
			final double linearPower,
			double zRotation,
			final boolean isQuickTurn
	) {
		double overPower, angularPower;

		if (isQuickTurn) {
			if (Math.abs(linearPower) < m_specifications.quickstopThreshold) {
				m_accumulator = (1 - m_specifications.alpha) * m_accumulator + m_specifications.alpha * limit(zRotation) * 2;
			}

			overPower = 1.0;
			angularPower = -zRotation;
		} else {
			overPower = 0.0;
			zRotation *= -signum(linearPower);
			angularPower = abs(linearPower) * zRotation * m_specifications.turnSensitivity - m_accumulator;

			if (m_accumulator > 1) {
				m_accumulator -= 1;
			} else if (m_accumulator < -1) {
				m_accumulator += 1;
			} else {
				m_accumulator = 0.0;
			}
		}

		double leftPower = linearPower - angularPower,
				rightPower = linearPower + angularPower;

		if (leftPower > 1.0) {
			rightPower -= overPower * (leftPower - 1.0);
			leftPower = 1.0;
		} else if (rightPower > 1.0) {
			leftPower -= overPower * (rightPower - 1.0);
			rightPower = 1.0;
		} else if (leftPower < -1.0) {
			rightPower += overPower * (-1.0 - leftPower);
			leftPower = -1.0;
		} else if (rightPower < -1.0) {
			leftPower += overPower * (-1.0 - rightPower);
			rightPower = -1.0;
		}

		drive(leftPower, rightPower);
	}

	public boolean turnToHeading(final double goal) {
		final var currentAngle = m_ahrs.getHalfAngle();

		double error = -(currentAngle - goal);

		if (abs(error) > 180) {
			error = 360 - error;

			if (currentAngle < 0 && goal > 0) {
				error *= -1;
			}
		}

		final boolean isAtHeading = abs(error) < DEGREES_THRESHOLD;

		if (!isAtHeading) {
			final double angularPower = error * m_specifications.kP_Rotational + copySign(m_specifications.kFF_Rotational, error);
			curvatureDrive(0, constrain(-1, angularPower, 1), true);
		}

		return isAtHeading;
	}

	public void stopMotors() {
		m_motors.forEach(CANSparkMax::stopMotor);
	}

	public List<CANSparkMax> getMotors() {
		return m_motors;
	}

	public CANSparkMax getMotor(final Side side) {
		return m_motors.get(side.idx);
	}

	private double rpm2InPerS(final double rpm) {
		final double rps = rpm / 60.0;
		return rps * m_specifications.rotationsToInches(m_transmission);
	}

	public double getInchesPerSecond(final Side side) {
		return getMotor(side).getEncoder().getVelocity() * side.encoderMultiplier;
	}

	public double getInchesPosition(final Side side) {
		return getMotor(side).getEncoder().getPosition() * side.encoderMultiplier;
	}

	public Specifications getLocals() {
		return m_specifications;
	}

	public DualTransmission getTransmission() {
		return m_transmission;
	}

	public MyAHRS getAHRS() {
		return m_ahrs;
	}

	public OpenLoopDriveCommand getDriveCommand() {
		return m_driveCommand;
	}

	public RobotStateEstimator getStateEstimator() {
		return RobotStateEstimator.getInstance();
	}

	public ShiftScheduler getShiftScheduler() {
		return m_shiftScheduler;
	}

	public enum Side {
		LEFT(0, 1), RIGHT(2, -1);

		protected final int idx, encoderMultiplier;

		Side(final int i, final int mult) {
			idx = i;
			encoderMultiplier = mult;
		}

		private static Side forIndex(final int i) {
			if (i < 2) {
				return LEFT;
			} else {
				return RIGHT;
			}
		}
	}

	public static class Specifications {
		public double width, length, wheelDiameter, maxVelocity,
				maxAcceleration, maxJerk, quickstopThreshold, turnSensitivity,
				alpha, kP_Rotational, kFF_Rotational;

		private Trajectory.Config m_config;

		public PIDF pidDrive;

		private PIDFVA pidTrajectory;

		public PIDFVA getPIDFVA() {
			if (pidTrajectory.kV == -1.0) {
				pidTrajectory.kV = 1 / maxVelocity;
			}

			return pidTrajectory;
		}

		public double wheelCircumference() {
			return Math.PI * wheelDiameter;
		}

		public double wheelRadius() {
			return wheelDiameter / 2.0;
		}

		public double rotationsToInches(final DualTransmission transmission) {
			// technical debt in the offseason lol
			return 1.411;
		}

		public double getEncoderFactor() {
			return rotationsToInches(null);
		}

		public Trajectory.Config getTrajectoryConfig() {
			if (Objects.isNull(m_config)) {
				m_config = new Trajectory.Config(
						Trajectory.FitMethod.HERMITE_CUBIC,
						Trajectory.Config.SAMPLES_FAST,
						dt,
						100,
						PathfinderFRC.DEFAULT_ACC * METER_TO_INCHES,
						PathfinderFRC.DEFAULT_JERK * METER_TO_INCHES
				);
			}

			return m_config;
		}
	}

	public static class DrivePower {
		private final double linear, angular;

		private DrivePower(final double l, final double a) {
			linear = l;
			angular = a;
		}

		public double linear() {
			return linear;
		}

		public double angular() {
			return angular;
		}

		public double left() {
			return linear - angular;
		}

		public double right() {
			return linear + angular;
		}
	}

	public DrivePower wheelVelocity(final double l, final double r) {
		final double linear = (m_specifications.wheelRadius() * (l + r)) / 2.0,
				angular = m_specifications.wheelRadius() * (r - l) / m_specifications.width;

		return new DrivePower(linear, angular);
	}

	public static DrivePower driveVelocity(final double linear, final double angular) {
		return new DrivePower(linear, angular);
	}

	@Override
	public void initDefaultCommand() { }

	@Override
	public void initSendable(final SendableBuilder builder) {
		builder.setSmartDashboardType("West Coast Drive");

		final List<Runnable> updaters = IntStream.range(0, 4).<Runnable>mapToObj(i -> {
			final CANSparkMax spark = m_motors.get(i);
			final String name = MOTOR_CONTROLLER_NAMES[i];

			final NetworkTableEntry
					setSpeedEntry = builder.getEntry(name + " Setpoint"),
					velocityEntry = builder.getEntry(name + " Velocity (In-s)"),
					positionEntry = builder.getEntry(name + " Position (In)");

			final String id = Integer.toString(spark.getDeviceId());
			builder.getEntry(name + " CAN ID").setString(id);

			return () -> {
				setSpeedEntry.setDouble(spark.get());
				velocityEntry.setDouble(getInchesPerSecond(Side.forIndex(i)));
				positionEntry.setDouble(getInchesPosition(Side.forIndex(i)));
			};
		}).collect(Collectors.toList());

		builder.setUpdateTable(() ->
			  updaters.forEach(Runnable::run));
	}
}
