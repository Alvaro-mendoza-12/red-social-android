type Point={x:number;y:number}
export function constrainPanel(point:Point,width:number,height:number,viewportWidth:number,viewportHeight:number):Point{
  return {x:Math.max(8,Math.min(point.x,viewportWidth-width-8)),y:Math.max(8,Math.min(point.y,viewportHeight-height-8))}
}
