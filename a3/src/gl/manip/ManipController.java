package gl.manip;

import java.awt.Component;
import java.util.HashMap;
import java.util.Map.Entry;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import blister.input.KeyboardEventDispatcher;
import blister.input.KeyboardKeyEventArgs;
import blister.input.MouseButton;
import blister.input.MouseButtonEventArgs;
import blister.input.MouseEventDispatcher;
import common.Scene;
import common.SceneObject;
import common.UUIDGenerator;
import common.event.SceneTransformationEvent;
import gl.PickingProgram;
import gl.RenderCamera;
import gl.RenderEnvironment;
import gl.RenderObject;
import gl.Renderer;
import form.ControlWindow;
import form.ScenePanel;
import egl.BlendState;
import egl.DepthState;
import egl.IDisposable;
import egl.RasterizerState;
import egl.math.Matrix4;
import egl.math.Vector2;
import egl.math.Vector3;
import ext.csharp.ACEventFunc;

public class ManipController implements IDisposable {
	public final ManipRenderer renderer = new ManipRenderer();
	public final HashMap<Manipulator, UUIDGenerator.ID> manipIDs = new HashMap<>();
	public final HashMap<Integer, Manipulator> manips = new HashMap<>();
	
	private final Scene scene;
	private final ControlWindow propWindow;
	private final ScenePanel scenePanel;
	private final RenderEnvironment rEnv;
	private ManipRenderer manipRenderer = new ManipRenderer();
	
	private final Manipulator[] currentManips = new Manipulator[3];
	private RenderObject currentObject = null;
	
	private Manipulator selectedManipulator = null;
	
	/**
	 * Is parent mode on?  That is, should manipulation happen in parent rather than object coordinates?
	 */
	private boolean parentSpace = false;
	
	/**
	 * Last seen mouse position in normalized coordinates
	 */
	private final Vector2 lastMousePos = new Vector2();
	
	public ACEventFunc<KeyboardKeyEventArgs> onKeyPress = new ACEventFunc<KeyboardKeyEventArgs>() {
		@Override
		public void receive(Object sender, KeyboardKeyEventArgs args) {
			if(selectedManipulator != null) return;
			switch (args.key) {
			case Keyboard.KEY_T:
				setCurrentManipType(Manipulator.Type.TRANSLATE);
				break;
			case Keyboard.KEY_R:
				setCurrentManipType(Manipulator.Type.ROTATE);
				break;
			case Keyboard.KEY_Y:
				setCurrentManipType(Manipulator.Type.SCALE);
				break;
			case Keyboard.KEY_P:
				parentSpace = !parentSpace;
				break;
			}
		}
	};
	public ACEventFunc<MouseButtonEventArgs> onMouseRelease = new ACEventFunc<MouseButtonEventArgs>() {
		@Override
		public void receive(Object sender, MouseButtonEventArgs args) {
			if(args.button == MouseButton.Right) {
				selectedManipulator = null;
			}
		}
	};
	
	public ManipController(RenderEnvironment re, Scene s, ControlWindow cw) {
		scene = s;
		propWindow = cw;
		Component o = cw.tabs.get("Object");
		scenePanel = o == null ? null : (ScenePanel)o;
		rEnv = re;
		
		// Give Manipulators Unique IDs
		manipIDs.put(Manipulator.ScaleX, scene.objects.getID("ScaleX"));
		manipIDs.put(Manipulator.ScaleY, scene.objects.getID("ScaleY"));
		manipIDs.put(Manipulator.ScaleZ, scene.objects.getID("ScaleZ"));
		manipIDs.put(Manipulator.RotateX, scene.objects.getID("RotateX"));
		manipIDs.put(Manipulator.RotateY, scene.objects.getID("RotateY"));
		manipIDs.put(Manipulator.RotateZ, scene.objects.getID("RotateZ"));
		manipIDs.put(Manipulator.TranslateX, scene.objects.getID("TranslateX"));
		manipIDs.put(Manipulator.TranslateY, scene.objects.getID("TranslateY"));
		manipIDs.put(Manipulator.TranslateZ, scene.objects.getID("TranslateZ"));
		for(Entry<Manipulator, UUIDGenerator.ID> e : manipIDs.entrySet()) {
			manips.put(e.getValue().id, e.getKey());
		}
		
		setCurrentManipType(Manipulator.Type.TRANSLATE);
	}
	@Override
	public void dispose() {
		manipRenderer.dispose();
		unhook();
	}
	
	private void setCurrentManipType(int type) {
		switch (type) {
		case Manipulator.Type.TRANSLATE:
			currentManips[Manipulator.Axis.X] = Manipulator.TranslateX;
			currentManips[Manipulator.Axis.Y] = Manipulator.TranslateY;
			currentManips[Manipulator.Axis.Z] = Manipulator.TranslateZ;
			break;
		case Manipulator.Type.ROTATE:
			currentManips[Manipulator.Axis.X] = Manipulator.RotateX;
			currentManips[Manipulator.Axis.Y] = Manipulator.RotateY;
			currentManips[Manipulator.Axis.Z] = Manipulator.RotateZ;
			break;
		case Manipulator.Type.SCALE:
			currentManips[Manipulator.Axis.X] = Manipulator.ScaleX;
			currentManips[Manipulator.Axis.Y] = Manipulator.ScaleY;
			currentManips[Manipulator.Axis.Z] = Manipulator.ScaleZ;
			break;
		}
	}
	
	public void hook() {
		KeyboardEventDispatcher.OnKeyPressed.add(onKeyPress);
		MouseEventDispatcher.OnMouseRelease.add(onMouseRelease);
	}
	public void unhook() {
		KeyboardEventDispatcher.OnKeyPressed.remove(onKeyPress);		
		MouseEventDispatcher.OnMouseRelease.remove(onMouseRelease);
	}
	
	/**
	 * Get the transformation that should be used to draw <manip> when it is being used to manipulate <object>.
	 * 
	 * This is just the object's or parent's frame-to-world transformation, but with a rotation appended on to 
	 * orient the manipulator along the correct axis.  One problem with the way this is currently done is that
	 * the manipulator can appear very small or large, or very squashed, so that it is hard to interact with.
	 * 
	 * @param manip The manipulator to be drawn (one axis of the complete widget)
	 * @param mViewProjection The camera (not needed for the current, simple implementation)
	 * @param object The selected object
	 * @return
	 */
	public Matrix4 getTransformation(Manipulator manip, RenderCamera camera, RenderObject object) {
		Matrix4 mManip = new Matrix4();
		
		switch (manip.axis) {
		case Manipulator.Axis.X:
			Matrix4.createRotationY((float)(Math.PI / 2.0), mManip);
			break;
		case Manipulator.Axis.Y:
			Matrix4.createRotationX((float)(-Math.PI / 2.0), mManip);
			break;
		case Manipulator.Axis.Z:
			mManip.setIdentity();
			break;
		}
		if (parentSpace) {
			if (object.parent != null)
				mManip.mulAfter(object.parent.mWorldTransform);
		} else
			mManip.mulAfter(object.mWorldTransform);

		return mManip;
	}
	
	/**
	 * Apply a transformation to <b>object</b> in response to an interaction with <b>manip</b> in which the user moved the mouse from
 	 * <b>lastMousePos</b> to <b>curMousePos</b> while viewing the scene through <b>camera</b>.  The manipulation happens differently depending
 	 * on the value of ManipController.parentMode; if it is true, the manipulator is aligned with the parent's coordinate system, 
 	 * or if it is false, with the object's local coordinate system.  
	 * @param manip The manipulator that is active (one axis of the complete widget)
	 * @param camera The camera (needed to map mouse motions into the scene)
	 * @param object The selected object (contains the transformation to be edited)
	 * @param lastMousePos The point where the mouse was last seen, in normalized [-1,1] x [-1,1] coordinates.
	 * @param curMousePos The point where the mouse is now, in normalized [-1,1] x [-1,1] coordinates.
	 */
	public void applyTransformation(Manipulator manip, RenderCamera camera, RenderObject object, Vector2 lastMousePos, Vector2 curMousePos) {

		// There are three kinds of manipulators; you can tell which kind you are dealing with by looking at manip.type.
		// Each type has three different axes; you can tell which you are dealing with by looking at manip.axis.

		// For rotation, you just need to apply a rotation in the correct space (either before or after the object's current
		// transformation, depending on the parent mode this.parentSpace).

		// For translation and scaling, the object should follow the mouse.  Following the assignment writeup, you will achieve
		// this by constructing the viewing rays and the axis in world space, and finding the t values *along the axis* where the
		// ray comes closest (not t values along the ray as in ray tracing).  To do this you need to transform the manipulator axis
		// from its frame (in which the coordinates are simple) to world space, and you need to get a viewing ray in world coordinates.

		// There are many ways to compute a viewing ray, but perhaps the simplest is to take a pair of points that are on the ray,
		// whose coordinates are simple in the canonical view space, and map them into world space using the appropriate matrix operations.
		
		// You may find it helpful to structure your code into a few helper functions; ours is about 150 lines.
		
		// TODO#A3#Part 4
				
		//convert click points to real world coordinates
		Vector3 lastM1 = new Vector3(lastMousePos.x,lastMousePos.y,-1);
		Vector3 lastM2 = new Vector3(lastMousePos.x,lastMousePos.y,1);
		
		//real world coordinates for the last mouse position
		Vector3 realLast1 = camera.mViewProjection.clone().invert().mulPos(lastM1);
		Vector3 realLast2 = camera.mViewProjection.clone().invert().mulPos(lastM2);
		
		Vector3 currM1 = new Vector3(curMousePos.x,curMousePos.y,-1);
		Vector3 currM2 = new Vector3(curMousePos.x,curMousePos.y,1);
		
		//real world coordinates for the current mouse position
		Vector3 realCurr1 = camera.mViewProjection.clone().invert().mulPos(currM1);
		Vector3 realCurr2 = camera.mViewProjection.clone().invert().mulPos(currM2);
		
		//set direction of the vectors
		Vector3 dirLastM = realLast2.clone().sub(realLast1);
		Vector3 dirCurrM = realCurr2.clone().sub(realCurr1);
		
		//set direction of normal of image plane
		Vector3 nImage = new Vector3(0,0,1);
		nImage.set(camera.mView.clone().invert().mulDir(nImage));
		
		//set direction of manipulator axis (in object space)
		Vector3 mAxis = new Vector3();
		if(manip.type == Manipulator.Axis.X){
			mAxis.set(1, 0, 0);
		}
		else if(manip.type == Manipulator.Axis.Y){
			mAxis.set(0, 1, 0);
		}
		else{
			mAxis.set(0, 0, 1);
		}
		
		//set direction of manipulator axis (in world space)
		if(!this.parentSpace){
			mAxis.set(object.mWorldTransform.clone().mulDir(mAxis));
		}
		else{
			mAxis.set(object.parent.mWorldTransform.clone().mulDir(mAxis));
		}
		//find origin of manipulator (in world space)
		Vector3 mOrigin = new Vector3(0,0,0);
		mOrigin.set(object.mWorldTransform.clone().mulPos(mOrigin));
		
		//direction of 2nd vector in manipulator plane (in world space)
		Vector3 mPerp = mAxis.clone().cross(nImage);
		
		//normal to manipulator plane (in world space)
		Vector3 mNorm = mAxis.clone().cross(mPerp);
		
		//normal of plane: (a,b,c)
		//origin of plane: (x0, y0, z0)
		//equation of the plane: a(x-x0)+ b(y-y0) + c(z-z0) = 0
		
		//find intersection of ray and plane
		float t1 = getIntersection(mOrigin, mNorm, realLast1, dirLastM);
		System.out.println(t1);
		Vector3 lastIntersect = dirLastM.clone().mul(t1).add(realLast1);
		float t2 = getIntersection(mOrigin, mNorm, realCurr1, dirCurrM);
		System.out.println(t2);
		Vector3 currIntersect = dirCurrM.clone().mul(t2).add(realCurr1);
		
		//find projection of intersection onto manipulator axis
		float t1Manip = getAxisProjection(mOrigin, mAxis, lastIntersect);
		float t2Manip = getAxisProjection(mOrigin, mAxis, currIntersect);
		
		Vector3 t1MVector = mAxis.clone().mul(t1Manip).add(mOrigin);
		Vector3 t2MVector = mAxis.clone().mul(t2Manip).add(mOrigin);
		
		float delta = t2MVector.len() - t1MVector.len();
		
		//System.out.println(manip.type);
		
		//scales
		if(manip.type == Manipulator.Type.SCALE){
			float factor = t2Manip/t1Manip;
			//float factor = secondPt/firstPt;
			String axis;
			Matrix4 transform = new Matrix4();
			if(this.parentSpace){
				if(manip.axis == Manipulator.Axis.X){
					transform.set(0,0,factor);
					object.sceneObject.transformation.mulBefore(transform);
					axis = "X";
				}
				else if(manip.axis == Manipulator.Axis.Y){
					transform.set(1,1,factor);
					object.sceneObject.transformation.mulBefore(transform);
					axis = "Y";
				}
				else{
					transform.set(2,2,factor);
					object.sceneObject.transformation.mulBefore(transform);
					axis = "Z";
				}
			}
			else{
				if(manip.axis == Manipulator.Axis.X){
					transform.set(0,0,factor);
					object.sceneObject.transformation.mulAfter(transform);
					axis = "X";
				}
				else if(manip.axis == Manipulator.Axis.Y){
					transform.set(1,1,factor);
					object.sceneObject.transformation.mulAfter(transform);
					axis = "Y";
				}
				else{
					transform.set(2,2,factor);
					object.sceneObject.transformation.mulAfter(transform);
					axis = "Z";
				}
			}
			System.out.println("Scale on " + axis);
			System.out.println(transform);
			System.out.println();
		}
		//rotate
		else if(manip.type == Manipulator.Type.ROTATE){
			//float angle = (float)(1/(Math.PI*2));
			Matrix4 transform = new Matrix4();
			float angle = (float)(delta/(Math.PI*2));
			String axis;
			if(this.parentSpace){
				if(manip.axis == Manipulator.Axis.X){
					transform.set(setRotationalTransform(0, angle));
					object.sceneObject.transformation.mulBefore(transform);
					axis = "X";
				}
				else if(manip.axis == Manipulator.Axis.Y){
					transform.set(setRotationalTransform(1, angle));
					object.sceneObject.transformation.mulBefore(transform);
					axis = "Y";
				}
				else{
					transform.set(setRotationalTransform(2, angle));
					object.sceneObject.transformation.mulBefore(transform);
					axis = "Z";
				}
			}
			else{
				if(manip.axis == Manipulator.Axis.X){
					transform.set(setRotationalTransform(0, angle));
					object.sceneObject.transformation.mulAfter(transform);
					axis = "X";
				}
				else if(manip.axis == Manipulator.Axis.Y){
					transform.set(setRotationalTransform(1, angle));
					object.sceneObject.transformation.mulAfter(transform);
					axis = "Y";
				}
				else{
					transform.set(setRotationalTransform(2, angle));
					object.sceneObject.transformation.mulAfter(transform);
					axis = "Z";
				}
			}
			System.out.println("Rotate on " + axis);
			System.out.println(transform);
			System.out.println();
		}
		//translate
		else{
			//float displacement = 1.0f;
			float displacement = (float)delta;
			String axis;
			Matrix4 transform = new Matrix4();
			if(this.parentSpace){
				if(manip.axis == Manipulator.Axis.X){
					transform.set(0,3,displacement);
					object.sceneObject.transformation.mulBefore(transform);
					axis = "X";
				}
				else if(manip.axis == Manipulator.Axis.Y){
					transform.set(1,3,displacement);
					object.sceneObject.transformation.mulBefore(transform);
					axis = "Y";
				}
				else{
					transform.set(2,3,displacement);
					object.sceneObject.transformation.mulBefore(transform);
					axis = "Z";
				}
			}
			else{
				if(manip.axis == Manipulator.Axis.X){
					transform.set(0,3,displacement);
					object.sceneObject.transformation.mulAfter(transform);
					axis = "X";
				}
				else if(manip.axis == Manipulator.Axis.Y){
					transform.set(1,3,displacement);
					object.sceneObject.transformation.mulAfter(transform);
					axis = "Y";
				}
				else{
					transform.set(2,3,displacement);
					object.sceneObject.transformation.mulAfter(transform);
					axis = "Z";
				}
			}
			System.out.println("Translate on " + axis);
			System.out.println(transform);
			System.out.println();
		}
	}
	
	public static float getAxisProjection(Vector3 rayOrigin, Vector3 rayDirection, Vector3 intersectionPt){
		Vector3 x1 = new Vector3();
		x1.set(rayOrigin);
		Vector3 x2 = new Vector3();
		x2.set(rayDirection.clone().mul(1).add(rayOrigin));
		Vector3 x0 = new Vector3();
		x0.set(intersectionPt);
		
		Vector3 x1Mx0 = new Vector3();
		x1Mx0.set(x1.clone().sub(x0));
		
		Vector3 x2Mx1 = new Vector3();
		x2Mx1.set(x2.clone().sub(x1));
		
		float result = - x1Mx0.clone().dot(x2Mx1)/x2Mx1.lenSq();
		return result;
	}
	
	public static float getIntersection(Vector3 planePt, Vector3 planeNorm, Vector3 ray0, Vector3 rayDir){
		float t = (planeNorm.x*(planePt.x-ray0.x) + planeNorm.y*(planePt.y-ray0.y) + planeNorm.z*(planePt.z-ray0.z))
				/(planeNorm.x*rayDir.x + planeNorm.y*rayDir.y + planeNorm.z*rayDir.z);
		return t;
		//normal of plane: (a,b,c)
		//origin of plane: (x0, y0, z0)
		//equation of the plane: a(x-x0)+ b(y-y0) + c(z-z0) = 0
				
		//direction of ray: (xd, yd, zd)
		//origin of ray: (x1, y1, z1)
		//equation of ray: o + td
		//	x: x1 + xd*t
		//	y: y1 + yd*t
		//	z: z1 + zd*t

		//a(x1 + xd*t - x0) + b(y1 + yd*t - y0) + c(z1 + zd*t - z0) = 0
		//a*x1 + a*xd*t - a*x0 + b*y1 + b*yd*t - b*y0 + c*z1 + c*zd*t - c*z0 = 0
		//(a*xd + b*yd + c*zd)t = a(x0-x1) + b(y0-y1) + c(z0-z1)
		//t = a(x0-x1) + b(y0-y1) + c(z0-z1)/(a*xd + b*yd + c*zd)
	}
	
	public static Matrix4 setRotationalTransform(int axis, float angle){
		Matrix4 transform = new Matrix4();
		//x-axis
		if(axis == 0){
			transform.set(1,1,(float)Math.cos(angle));
			transform.set(1,2,-(float)Math.sin(angle));
			transform.set(2,1,(float)Math.sin(angle));
			transform.set(2,2,(float)Math.cos(angle));
			return transform;
		}
		//y-axis
		else if(axis == 1){
			transform.set(0,0,(float)Math.cos(angle));
			transform.set(2,0,-(float)Math.sin(angle));
			transform.set(0,2,(float)Math.sin(angle));
			transform.set(2,2,(float)Math.cos(angle));
			return transform;
		}
		//z-axis
		else{
			transform.set(0,0,(float)Math.cos(angle));
			transform.set(1,0,(float)Math.sin(angle));
			transform.set(1,0,-(float)Math.sin(angle));
			transform.set(1,1,(float)Math.cos(angle));
			return transform;
		}
	}
	
	public static float[] getClosestPt(Vector3 origin, Vector3 direction, int axis){
		Vector3 manipAxis = new Vector3();
		float[] coordinates = new float[2];
		if(axis == 0){
			//x-axis
			manipAxis.set(1.0f, 0.0f, 0.0f);
		}
		else if(axis == 1){
			//y-axis
			manipAxis.set(0.0f, 1.0f, 0.0f);
		}
		else{
			//z-axis
			manipAxis.set(0.0f, 0.0f, 1.0f);
		}
		float a = direction.clone().lenSq();
		float b = direction.clone().dot(manipAxis);
		float c = manipAxis.clone().lenSq();
		float d = direction.clone().dot(origin);
		float e = manipAxis.clone().dot(origin);
		
		//closest point for mouse ray
		float t_m = (b*e-c*d)/(a*c-b*b);
		//closest point for axis ray
		float t_a = (a*e-b*d)/(a*c-b*b);
		coordinates[0] = t_m;
		coordinates[1] = t_a;
		System.out.println(coordinates);
		return coordinates;
	}
		
	public void checkMouse(int mx, int my, RenderCamera camera) {
		Vector2 curMousePos = new Vector2(mx, my).add(0.5f).mul(2).div(camera.viewportSize.x, camera.viewportSize.y).sub(1);
		if(curMousePos.x != lastMousePos.x || curMousePos.y != lastMousePos.y) {
			if(selectedManipulator != null && currentObject != null) {
				applyTransformation(selectedManipulator, camera, currentObject, lastMousePos, curMousePos);
				scene.sendEvent(new SceneTransformationEvent(currentObject.sceneObject));
			}
			lastMousePos.set(curMousePos);
		}
	}

	public void checkPicking(Renderer renderer, RenderCamera camera, int mx, int my) {
		if(camera == null) return;
		
		// Pick An Object
		renderer.beginPickingPass(camera);
		renderer.drawPassesPick();
		if(currentObject != null) {
			// Draw Object Manipulators
			GL11.glClearDepth(1.0);
			GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
			
			DepthState.DEFAULT.set();
			BlendState.OPAQUE.set();
			RasterizerState.CULL_NONE.set();
			
			drawPick(camera, currentObject, renderer.pickProgram);
		}
		int id = renderer.getPickID(Mouse.getX(), Mouse.getY());
		
		selectedManipulator = manips.get(id);
		if(selectedManipulator != null) {
			// Begin Manipulator Operations
			System.out.println("Selected Manip: " + selectedManipulator.type + " " + selectedManipulator.axis);
			return;
		}
		
		SceneObject o = scene.objects.get(id);
		if(o != null) {
			System.out.println("Picked An Object: " + o.getID().name);
			if(scenePanel != null) {
				scenePanel.select(o.getID().name);
				propWindow.tabToForefront("Object");
			}
			currentObject = rEnv.findObject(o);
		}
		else if(currentObject != null) {
			currentObject = null;
		}
	}
	
	public RenderObject getCurrentObject() {
		return currentObject;
	}
	
	public void draw(RenderCamera camera) {
		if(currentObject == null) return;
		
		DepthState.NONE.set();
		BlendState.ALPHA_BLEND.set();
		RasterizerState.CULL_CLOCKWISE.set();
		
		for(Manipulator manip : currentManips) {
			Matrix4 mTransform = getTransformation(manip, camera, currentObject);
			manipRenderer.render(mTransform, camera.mViewProjection, manip.type, manip.axis);
		}
		
		DepthState.DEFAULT.set();
		BlendState.OPAQUE.set();
		RasterizerState.CULL_CLOCKWISE.set();
		
		for(Manipulator manip : currentManips) {
			Matrix4 mTransform = getTransformation(manip, camera, currentObject);
			manipRenderer.render(mTransform, camera.mViewProjection, manip.type, manip.axis);
		}

}
	public void drawPick(RenderCamera camera, RenderObject ro, PickingProgram prog) {
		for(Manipulator manip : currentManips) {
			Matrix4 mTransform = getTransformation(manip, camera, ro);
			prog.setObject(mTransform, manipIDs.get(manip).id);
			manipRenderer.drawCall(manip.type, prog.getPositionAttributeLocation());
		}
	}
	
}
