import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import javax.swing.*;

// Model View Presenter
// Presenter erzeugt Ticker -> Ticker ruft zyklisch UpdateRequest auf -> UpdateRequest fragt Model ab und lässt View den neuen Status abfragen

// hält einen flightHandler (extends Thread) solange die Drone fliegt, dieser wird nach jedem Klick auf 'Start' neu erzeugt
class DroneModel
{
	private flightHandler fh;

	public void start(ArrayList<String> commands){

		fh = new flightHandler();
		fh.setCommands(commands);
		fh.start();
	}

	public String getStatus(){

		if(fh.isAlive()) {
			return fh.getStatus();
		}else{
			return "keine Mission / Mission abgeschlossen";
		}
	}
}

// === UpdateRequest ===
class UpdateRequest implements Runnable
{
	private DroneModel model;
	private DroneView view;

	public UpdateRequest(DroneModel model, DroneView view) {
		this.model = model;
		this.view  = view;
	}

	public void run() {
		Thread.currentThread().setName("UpdateRequest");
		view.showState( model.getStatus() );
	}
}

// === zyklisches Anstossen des Presenters ===
class Ticker extends Thread
{
	private final static long UPDATE_INTERVAL = 10; // Milliseconds
	private UpdateRequest updateReq;
	volatile boolean alive;

	public Ticker(DroneModel model, DroneView view) {
		updateReq = new UpdateRequest(model, view);
		alive = true;
		start();
	}

	public void end(){
		alive = false;
	}

	public void run() {

		Thread.currentThread().setName("Ticker");

		try {
			while(!isInterrupted() && alive) {
				EventQueue.invokeLater(updateReq);
				Thread.sleep(UPDATE_INTERVAL);
			}
		}
		catch(InterruptedException e) { }
	}
}

// === Presenter ===
class DronePresenter implements ActionListener
{
	protected 	DroneModel 	model;
	protected 	DroneView 	view;
	private 	Ticker 		ticker;

	public void setModelAndView(DroneModel model, DroneView view)  {
		this.model = model;
		this.view  = view;
	}

	public void actionPerformed(ActionEvent e)  {
		String s = e.getActionCommand();
		if(s.equals("Start"))  {

			if(ticker != null) ticker.end();

			ticker = new Ticker(model, view);
			model.start(parseInput(view.getInput()));
		}
	}

	private ArrayList<String> parseInput(String input){

		ArrayList<String> commands = new ArrayList<String>();
		String parts[] = input.split(";");

		for(int i = 0; i < parts.length; i++){
			commands.add(parts[i]);
		}
		return commands;
	}
}

// === View ===
class DroneView
{
	private DronePresenter presenter;
	private JLabel  label;
	private JTextField input;

	public DroneView( DronePresenter presenter) {
		this.presenter = presenter;
		initView();
	}

	public String getInput(){

		return input.getText();
	}

	private void initView() {

		int fontSizeToUse = 18;
		Font font;

		JFrame f = new JFrame("Drone");
		f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		f.setLayout(new GridLayout(0, 1));

		input = new JTextField();
		font = input.getFont();
		input.setFont(new Font(font.getName(), Font.PLAIN, fontSizeToUse));
		f.add(input);

		label = new JLabel("", SwingConstants.RIGHT);
		font = label.getFont();
		label.setFont(new Font(font.getName(), Font.PLAIN, fontSizeToUse));
		f.add(label);

		JButton b1 = new JButton("Start");
		font = b1.getFont();
		b1.setFont(new Font(font.getName(), Font.PLAIN, fontSizeToUse));
		f.add(b1);
		b1.addActionListener(presenter);

		f.setLocation(300, 50);
		f.setSize(800, 800);
		f.setVisible(true);
	}

	public void showState(String state) {

		label.setText(state);
	}
}

// ===== Main =====
public class DroneManager
{
	public static void main(String[] args) {
		DronePresenter p  = new DronePresenter();
		DroneView    view = new DroneView(p);
		DroneModel model  = new DroneModel();
		p.setModelAndView(model, view);
	}
}
